package tech.wenisch.proxera.service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tech.wenisch.proxera.domain.IngressSpec;

@Service
public class IngressService {

    private static final Logger log = LoggerFactory.getLogger(IngressService.class);

    private static final Path SA_TOKEN = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SA_NAMESPACE = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
    private static final Path SA_CA_CERT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

    private final boolean available;
    private final String namespace;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;
    private volatile HttpClient client;
    private volatile boolean clientInitAttempted;
    private final Object clientInitLock = new Object();

    public IngressService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        boolean detected = false;
        String detectedNamespace = null;
        String detectedApiBaseUrl = null;

        if (Files.exists(SA_TOKEN)) {
            try {
                detectedNamespace = Files.readString(SA_NAMESPACE).trim();
                String host = System.getenv("KUBERNETES_SERVICE_HOST");
                String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT_HTTPS",
                        System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443"));
                if (host != null && !host.isBlank()) {
                    detectedApiBaseUrl = "https://" + host + ":" + port;
                    detected = true;
                    log.info("Kubernetes service account detected, namespace={}, api={}",
                            detectedNamespace, detectedApiBaseUrl);
                } else {
                    log.warn("Kubernetes service account found but KUBERNETES_SERVICE_HOST is not set");
                }
            } catch (Exception e) {
                log.warn("Kubernetes environment detection failed", e);
            }
        } else {
            log.debug("Not running in Kubernetes - ingress management unavailable");
        }

        this.available = detected;
        this.namespace = detectedNamespace;
        this.apiBaseUrl = detectedApiBaseUrl;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getNamespace() {
        return namespace;
    }

    public List<IngressSpec> listIngresses() {
        if (!available || getClient() == null) {
            return Collections.emptyList();
        }

        try {
            JsonNode response = sendJson("GET", ingressCollectionPath(), null);
            JsonNode items = response.path("items");
            if (!items.isArray()) {
                return Collections.emptyList();
            }
            return StreamSupport.stream(items.spliterator(), false)
                    .map(this::toSpec)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list ingresses", e);
            return Collections.emptyList();
        }
    }

    public void createIngress(IngressSpec spec) {
        ensureClientAvailable();
        sendJson("POST", ingressCollectionPath(), buildIngress(spec));
    }

    public void replaceIngress(String name, IngressSpec spec) {
        ensureClientAvailable();
        JsonNode existing = sendJson("GET", ingressResourcePath(name), null);
        ObjectNode updated = buildIngress(spec);
        JsonNode resourceVersion = existing.path("metadata").path("resourceVersion");
        if (!resourceVersion.isMissingNode() && !resourceVersion.asText().isBlank()) {
            ((ObjectNode) updated.path("metadata")).put("resourceVersion", resourceVersion.asText());
        }
        sendJson("PUT", ingressResourcePath(name), updated);
    }

    public void deleteIngress(String name) {
        ensureClientAvailable();
        sendJson("DELETE", ingressResourcePath(name), null);
    }

    private HttpClient getClient() {
        if (!available) {
            return null;
        }

        HttpClient existing = client;
        if (existing != null) {
            return existing;
        }
        if (clientInitAttempted) {
            return null;
        }

        synchronized (clientInitLock) {
            if (client != null) {
                return client;
            }
            if (clientInitAttempted) {
                return null;
            }

            clientInitAttempted = true;
            try {
                client = HttpClient.newBuilder()
                        .sslContext(buildSslContext())
                        .build();
                log.info("Kubernetes in-cluster REST client initialized, namespace={}", namespace);
            } catch (Exception e) {
                log.warn("Kubernetes client init failed", e);
            }
            return client;
        }
    }

    private ObjectNode buildIngress(IngressSpec spec) {
        String backendSvc = System.getenv("PROXERA_K8S_SERVICE_NAME");
        if (backendSvc == null || backendSvc.isBlank()) {
            backendSvc = "proxera";
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", "networking.k8s.io/v1");
        root.put("kind", "Ingress");

        ObjectNode metadata = root.putObject("metadata");
        metadata.put("name", spec.getName());
        metadata.put("namespace", namespace);
        if (spec.getAnnotations() != null && !spec.getAnnotations().isEmpty()) {
            ObjectNode annotations = metadata.putObject("annotations");
            spec.getAnnotations().forEach(annotations::put);
        }

        ObjectNode ingressSpec = root.putObject("spec");
        if (spec.getClassName() != null && !spec.getClassName().isBlank()) {
            ingressSpec.put("ingressClassName", spec.getClassName());
        }

        ObjectNode backendServicePort = objectMapper.createObjectNode();
        backendServicePort.put("number", 8080);

        ObjectNode backendService = objectMapper.createObjectNode();
        backendService.put("name", backendSvc);
        backendService.set("port", backendServicePort);

        ObjectNode backend = objectMapper.createObjectNode();
        backend.set("service", backendService);

        ObjectNode path = objectMapper.createObjectNode();
        path.put("path", spec.getPath() != null && !spec.getPath().isBlank() ? spec.getPath() : "/");
        path.put("pathType", spec.getPathType() != null && !spec.getPathType().isBlank()
                ? spec.getPathType()
                : "ImplementationSpecific");
        path.set("backend", backend);

        ObjectNode http = objectMapper.createObjectNode();
        http.set("paths", objectMapper.createArrayNode().add(path));

        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("host", spec.getHost());
        rule.set("http", http);
        ingressSpec.set("rules", objectMapper.createArrayNode().add(rule));

        if (spec.isTlsEnabled() && spec.getTlsSecretName() != null && !spec.getTlsSecretName().isBlank()) {
            ObjectNode tls = objectMapper.createObjectNode();
            tls.set("hosts", objectMapper.createArrayNode().add(spec.getHost()));
            tls.put("secretName", spec.getTlsSecretName());
            ingressSpec.set("tls", objectMapper.createArrayNode().add(tls));
        }

        return root;
    }

    private IngressSpec toSpec(JsonNode ingress) {
        IngressSpec spec = new IngressSpec();

        JsonNode metadata = ingress.path("metadata");
        JsonNode ingressSpec = ingress.path("spec");
        spec.setName(metadata.path("name").asText());
        spec.setNamespace(metadata.path("namespace").asText(namespace));
        spec.setAnnotations(toStringMap(metadata.path("annotations")));
        spec.setClassName(ingressSpec.path("ingressClassName").asText(null));

        JsonNode rules = ingressSpec.path("rules");
        if (rules.isArray() && !rules.isEmpty()) {
            JsonNode rule = rules.get(0);
            spec.setHost(rule.path("host").asText());
            JsonNode paths = rule.path("http").path("paths");
            if (paths.isArray() && !paths.isEmpty()) {
                JsonNode path = paths.get(0);
                spec.setPath(path.path("path").asText());
                spec.setPathType(path.path("pathType").asText());
            }
        }

        JsonNode tlsEntries = ingressSpec.path("tls");
        if (tlsEntries.isArray() && !tlsEntries.isEmpty()) {
            JsonNode tls = tlsEntries.get(0);
            spec.setTlsEnabled(true);
            spec.setTlsSecretName(tls.path("secretName").asText());
        }

        return spec;
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return result;
    }

    private void ensureClientAvailable() {
        if (!available || getClient() == null) {
            throw new IllegalStateException("Kubernetes API is not available in this runtime");
        }
    }

    private JsonNode sendJson(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + path))
                    .header("Authorization", "Bearer " + Files.readString(SA_TOKEN).trim())
                    .header("Accept", "application/json");

            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }

            HttpResponse<String> response = getClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Kubernetes API " + method + " " + path + " failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes API request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kubernetes API request failed: " + e.getMessage(), e);
        }
    }

    private String ingressCollectionPath() {
        return "/apis/networking.k8s.io/v1/namespaces/" + encodePathSegment(namespace) + "/ingresses";
    }

    private String ingressResourcePath(String name) {
        return ingressCollectionPath() + "/" + encodePathSegment(name);
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private SSLContext buildSslContext() throws Exception {
        if (!Files.exists(SA_CA_CERT)) {
            return SSLContext.getDefault();
        }

        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(SA_CA_CERT)) {
            certificate = (X509Certificate) certificateFactory.generateCertificate(in);
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("kubernetes-service-account-ca", certificate);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }
}
