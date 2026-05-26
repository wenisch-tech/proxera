package tech.wenisch.proxera.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.TopologyEvent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.RoutingService;
import tech.wenisch.proxera.service.SettingsService;
import tech.wenisch.proxera.tunnel.RequestPayload;
import tech.wenisch.proxera.tunnel.ResponsePayload;

@Service
@Slf4j
public class ProxyService {

    private static final long TIMEOUT_MS = 30_000;
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade"
    );

    private final RoutingService routingService;
    private final MessageBus messageBus;
    private final AccessLogService accessLogService;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public ProxyService(RoutingService routingService,
                        MessageBus messageBus,
                        AccessLogService accessLogService,
                        ObjectMapper objectMapper,
                        SettingsService settingsService) {
        this.routingService = routingService;
        this.messageBus = messageBus;
        this.accessLogService = accessLogService;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public void proxy(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext) {
        String host = request.getHeader("Host");
        String path = request.getRequestURI();

        routingService.resolve(host, path).ifPresentOrElse(
                routeDomain -> dispatchToAgent(routeDomain, request, response, asyncContext),
                () -> {
                    try {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    } catch (IOException e) {
                        log.warn("Could not send 404", e);
                    } finally {
                        asyncContext.complete();
                    }
                }
        );
    }

    private void dispatchToAgent(RouteDomain routeDomain, HttpServletRequest request,
                                  HttpServletResponse response, AsyncContext asyncContext) {
        Route route = routeDomain.getRoute();
        try {
            String correlationId = UUID.randomUUID().toString();
            RequestPayload payload = buildPayload(routeDomain, request);
            String requestJson = objectMapper.writeValueAsString(payload);

            UUID agentId = route.getAgent().getId();
            messageBus.publishTopology(new TopologyEvent("REQUEST_IN_FLIGHT", agentId.toString(),
                    route.getName()));

            CompletableFuture<ResponsePayload> future = messageBus.dispatch(agentId, requestJson, correlationId);

            future.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((resp, ex) -> {
                if (ex != null) {
                    log.warn("Tunnel request timed out or failed: {}", ex.getMessage());
                    try {
                        response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Bad Gateway");
                    } catch (IOException ioEx) {
                        log.error("Failed to write 502", ioEx);
                    } finally {
                        asyncContext.complete();
                    }
                } else {
                    messageBus.publishTopology(new TopologyEvent("REQUEST_COMPLETED", agentId.toString(),
                            route.getName()));
                    accessLogService.log(route, agentId, request, resp);
                    try {
                        writeResponse(resp, response, routeDomain);
                    } catch (IOException ioEx) {
                        log.error("Failed to write proxy response", ioEx);
                    } finally {
                        asyncContext.complete();
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error dispatching proxy request", e);
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (IOException ioEx) {
                log.error("Failed to write 500", ioEx);
            } finally {
                asyncContext.complete();
            }
        }
    }

    private static final Pattern CSS_ABSOLUTE_URL_PATTERN =
            Pattern.compile("url\\(\\s*(['\"]?)(/)([^'\"()]+)\\1\\s*\\)");

    private static final Set<String> REWRITE_ATTRIBUTES =
            Set.of("href", "src", "action", "data-src", "data-href", "data-url");

    private void writeResponse(ResponsePayload resp, HttpServletResponse response, RouteDomain routeDomain)
            throws IOException {

        // Determine whether path-prefix rewriting is needed
        String prefix = null;
        if (routeDomain.isStripPrefix() && settingsService.get().isRewriteUrls()) {
            String raw = routeDomain.getPathPrefix();
            if (raw != null && !raw.isBlank()) {
                prefix = "/" + raw.strip().replaceAll("^/+", "").replaceAll("/+$", "");
            }
        }

        response.setStatus(resp.status());

        // Track Content-Type and Content-Encoding before writing headers
        String contentType = null;
        String contentEncoding = null;
        for (Map.Entry<String, List<String>> entry : resp.headers().entrySet()) {
            String lower = entry.getKey().toLowerCase();
            List<String> vals = entry.getValue();
            if (vals != null && !vals.isEmpty()) {
                if (lower.equals("content-type")) contentType = vals.get(0);
                if (lower.equals("content-encoding")) contentEncoding = vals.get(0);
            }
        }

        final String effectivePrefix = prefix;
        final boolean rewriting = effectivePrefix != null;
        final boolean gzipped = rewriting && contentEncoding != null
                && contentEncoding.toLowerCase().contains("gzip");

        resp.headers().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(lower)) return;
            // Drop Content-Length when rewriting (body size will change)
            if (rewriting && lower.equals("content-length")) return;
            // Drop Content-Encoding when we decompress
            if (gzipped && lower.equals("content-encoding")) return;
            for (String value : values) {
                // Rewrite Location header for redirects
                if (rewriting && lower.equals("location") && value.startsWith("/")) {
                    response.addHeader(name, effectivePrefix + value);
                } else {
                    response.addHeader(name, value);
                }
            }
        });

        byte[] body = resp.body() != null ? Base64.getDecoder().decode(resp.body()) : new byte[0];

        if (!rewriting || body.length == 0) {
            response.getOutputStream().write(body);
            return;
        }

        // Decompress if needed
        if (gzipped) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body));
                 ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                gz.transferTo(buf);
                body = buf.toByteArray();
            }
        }

        String ct = contentType != null ? contentType.toLowerCase() : "";
        if (ct.contains("text/html")) {
            body = rewriteHtml(body, ct, effectivePrefix);
        } else if (ct.contains("text/css")) {
            body = rewriteCss(body, ct, effectivePrefix);
        }

        response.getOutputStream().write(body);
    }

    private byte[] rewriteHtml(byte[] body, String contentType, String prefix) {
        Charset charset = charsetFrom(contentType);
        Document doc = Jsoup.parse(new String(body, charset));
        doc.outputSettings().charset(charset).syntax(Document.OutputSettings.Syntax.html);

        // Inject a runtime shim as the very first script in <head> so it runs
        // before any application code and intercepts fetch/XHR/history calls.
        Element shim = doc.head().prependElement("script");
        shim.appendChild(new DataNode(buildPrefixShim(prefix)));

        // Rewrite element attributes
        for (String attr : REWRITE_ATTRIBUTES) {
            Elements elements = doc.select("[" + attr + "]");
            for (Element el : elements) {
                String val = el.attr(attr);
                if (val.startsWith("/") && !val.startsWith("//")) {
                    el.attr(attr, prefix + val);
                }
            }
        }

        // Rewrite inline <style> blocks and style attributes
        for (Element style : doc.select("style")) {
            style.html(rewriteCssText(style.html(), prefix));
        }
        for (Element el : doc.select("[style]")) {
            String inlineStyle = el.attr("style");
            el.attr("style", rewriteCssText(inlineStyle, prefix));
        }

        return doc.outerHtml().getBytes(charset);
    }

    /**
     * Builds a self-contained JS shim that rewrites absolute paths at runtime.
     * Patches fetch, XMLHttpRequest, and the History API so that any absolute
     * path (starting with /) is transparently prefixed — regardless of whether
     * the URL was hard-coded or constructed dynamically in application code.
     * Already-prefixed URLs are skipped to prevent double-rewriting.
     */
    private static String buildPrefixShim(String prefix) {
        return "(function(){" +
               "var p='" + prefix + "';" +
               "function rw(u){" +
               "return(typeof u==='string'&&u.charAt(0)==='/'&&u.charAt(1)!=='/'" +
               "&&u.indexOf(p+'/')!==0&&u!==p)?p+u:u;}" +
               "var of=window.fetch;" +
               "if(of)window.fetch=function(u,o){return of.call(this,rw(u),o);};" +
               "var ox=XMLHttpRequest.prototype.open;" +
               "XMLHttpRequest.prototype.open=function(){" +
               "arguments[1]=rw(arguments[1]);return ox.apply(this,arguments);};" +
               "var ops=history.pushState,ors=history.replaceState;" +
               "if(ops)history.pushState=function(s,t,u){return ops.call(this,s,t,rw(u));};" +
               "if(ors)history.replaceState=function(s,t,u){return ors.call(this,s,t,rw(u));};" +
               "})();";
    }

    private byte[] rewriteCss(byte[] body, String contentType, String prefix) {
        Charset charset = charsetFrom(contentType);
        String text = new String(body, charset);
        return rewriteCssText(text, prefix).getBytes(charset);
    }

    private String rewriteCssText(String css, String prefix) {
        return CSS_ABSOLUTE_URL_PATTERN.matcher(css)
                .replaceAll(m -> "url(" + m.group(1) + prefix + "/" + m.group(3) + m.group(1) + ")");
    }

    private static Charset charsetFrom(String contentType) {
        if (contentType == null) return StandardCharsets.UTF_8;
        for (String part : contentType.split(";")) {
            String t = part.strip();
            if (t.toLowerCase().startsWith("charset=")) {
                try {
                    return Charset.forName(t.substring(8).strip());
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private RequestPayload buildPayload(RouteDomain routeDomain, HttpServletRequest request) throws IOException {
        Route route = routeDomain.getRoute();
        Map<String, List<String>> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(name)) {
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }

        // X-Forwarded-For: append client IP to any existing chain (multi-hop proxy support).
        String clientIp = request.getRemoteAddr();
        List<String> existingXffList = headers.get("x-forwarded-for");
        String xffValue = (existingXffList != null && !existingXffList.isEmpty() && !existingXffList.get(0).isBlank())
                ? existingXffList.get(0) + ", " + clientIp
                : clientIp;
        headers.put("x-forwarded-for", List.of(xffValue));

        // X-Forwarded-Host: use the full Host header including port.
        String hostHeader = request.getHeader("Host");
        headers.put("x-forwarded-host", List.of(hostHeader != null ? hostHeader : request.getServerName()));

        headers.put("x-forwarded-proto", List.of(request.getScheme()));
        headers.put("x-forwarded-port", List.of(String.valueOf(request.getServerPort())));

        headers.put("x-real-ip", List.of(clientIp));

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String body = bodyBytes.length > 0 ? Base64.getEncoder().encodeToString(bodyBytes) : null;

        return new RequestPayload(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                headers,
                body,
                route.getLocalHost(),
                route.getLocalPort(),
                routeDomain.isStripPrefix() ? routeDomain.getPathPrefix() : null,
                request.getRemoteAddr()
        );
    }
}
