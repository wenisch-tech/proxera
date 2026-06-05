package tech.wenisch.proxera.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import tech.wenisch.proxera.domain.IngressSpec;

@Service
public class IngressService {

    private static final Logger log = LoggerFactory.getLogger(IngressService.class);

    private static final Path SA_TOKEN     = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SA_NAMESPACE = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");

    private final boolean available;
    private final String namespace;
    private volatile KubernetesClient client;
    private volatile boolean clientInitAttempted;
    private final Object clientInitLock = new Object();

    public IngressService() {
        boolean avail = false;
        String ns = null;
        if (Files.exists(SA_TOKEN)) {
            try {
                ns = Files.readString(SA_NAMESPACE).trim();
                avail = true;
                log.info("Kubernetes service account detected, namespace={}", ns);
            } catch (Exception e) {
                log.warn("Kubernetes environment detection failed: {}", e.getMessage());
            }
        } else {
            log.debug("Not running in Kubernetes — ingress management unavailable");
        }
        this.available = avail;
        this.namespace = ns;
        this.client = null;
        this.clientInitAttempted = false;
    }

    private KubernetesClient getClient() {
        if (!available) {
            return null;
        }
        KubernetesClient k8s = client;
        if (k8s != null) {
            return k8s;
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
                client = new KubernetesClientBuilder().build();
                log.info("Kubernetes in-cluster client initialized, namespace={}", namespace);
            } catch (Exception e) {
                log.warn("Kubernetes client init failed: {}", e.getMessage());
            }
            return client;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getNamespace() {
        return namespace;
    }

    public List<IngressSpec> listIngresses() {
        KubernetesClient k8s = getClient();
        if (!available || k8s == null) return Collections.emptyList();
        try {
            return k8s.network().v1().ingresses().inNamespace(namespace).list().getItems()
                    .stream().map(this::toSpec).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to list ingresses: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void createIngress(IngressSpec spec) {
        KubernetesClient k8s = getClient();
        if (!available || k8s == null) throw new IllegalStateException("Kubernetes not available");
        k8s.network().v1().ingresses().inNamespace(namespace).resource(buildIngress(spec)).create();
    }

    public void replaceIngress(String name, IngressSpec spec) {
        KubernetesClient k8s = getClient();
        if (!available || k8s == null) throw new IllegalStateException("Kubernetes not available");
        Ingress existing = k8s.network().v1().ingresses().inNamespace(namespace).withName(name).get();
        Ingress updated = buildIngress(spec);
        if (existing != null && existing.getMetadata() != null) {
            updated.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
        }
        k8s.network().v1().ingresses().inNamespace(namespace).resource(updated).update();
    }

    public void deleteIngress(String name) {
        KubernetesClient k8s = getClient();
        if (!available || k8s == null) throw new IllegalStateException("Kubernetes not available");
        k8s.network().v1().ingresses().inNamespace(namespace).withName(name).delete();
    }

    private Ingress buildIngress(IngressSpec spec) {
        String backendSvc = System.getenv("PROXERA_K8S_SERVICE_NAME");
        if (backendSvc == null || backendSvc.isBlank()) backendSvc = "proxera";

        HTTPIngressPath ingressPath = new HTTPIngressPathBuilder()
                .withPath(spec.getPath() != null ? spec.getPath() : "/")
                .withPathType(spec.getPathType() != null ? spec.getPathType() : "ImplementationSpecific")
                .withNewBackend()
                    .withNewService()
                        .withName(backendSvc)
                        .withNewPort().withNumber(8080).endPort()
                    .endService()
                .endBackend()
                .build();

        IngressRule rule = new IngressRuleBuilder()
                .withHost(spec.getHost())
                .withNewHttp().withPaths(ingressPath).endHttp()
                .build();

        IngressSpecBuilder specBuilder = new IngressSpecBuilder()
                .withIngressClassName(spec.getClassName())
                .withRules(rule);

        if (spec.isTlsEnabled() && spec.getTlsSecretName() != null && !spec.getTlsSecretName().isBlank()) {
            specBuilder.withTls(new IngressTLSBuilder()
                    .withHosts(spec.getHost())
                    .withSecretName(spec.getTlsSecretName())
                    .build());
        }

        return new IngressBuilder()
                .withNewMetadata()
                    .withName(spec.getName())
                    .withNamespace(namespace)
                    .withAnnotations(spec.getAnnotations())
                .endMetadata()
                .withSpec(specBuilder.build())
                .build();
    }

    private IngressSpec toSpec(Ingress ingress) {
        IngressSpec spec = new IngressSpec();
        spec.setName(ingress.getMetadata().getName());
        spec.setNamespace(ingress.getMetadata().getNamespace());
        spec.setAnnotations(ingress.getMetadata().getAnnotations() != null
                ? new LinkedHashMap<>(ingress.getMetadata().getAnnotations())
                : new LinkedHashMap<>());
        if (ingress.getSpec() != null) {
            spec.setClassName(ingress.getSpec().getIngressClassName());
            if (ingress.getSpec().getRules() != null && !ingress.getSpec().getRules().isEmpty()) {
                IngressRule rule = ingress.getSpec().getRules().get(0);
                spec.setHost(rule.getHost());
                if (rule.getHttp() != null && !rule.getHttp().getPaths().isEmpty()) {
                    HTTPIngressPath p = rule.getHttp().getPaths().get(0);
                    spec.setPath(p.getPath());
                    spec.setPathType(p.getPathType());
                }
            }
            if (ingress.getSpec().getTls() != null && !ingress.getSpec().getTls().isEmpty()) {
                IngressTLS tls = ingress.getSpec().getTls().get(0);
                spec.setTlsEnabled(true);
                spec.setTlsSecretName(tls.getSecretName());
            }
        }
        return spec;
    }
}
