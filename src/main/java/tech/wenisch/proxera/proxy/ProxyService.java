package tech.wenisch.proxera.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.TopologyEvent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.RoutingService;
import tech.wenisch.proxera.tunnel.RequestPayload;
import tech.wenisch.proxera.tunnel.ResponsePayload;

import java.io.IOException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public ProxyService(RoutingService routingService,
                        MessageBus messageBus,
                        AccessLogService accessLogService,
                        ObjectMapper objectMapper) {
        this.routingService = routingService;
        this.messageBus = messageBus;
        this.accessLogService = accessLogService;
        this.objectMapper = objectMapper;
    }

    public void proxy(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext) {
        String host = request.getHeader("Host");
        String path = request.getRequestURI();

        routingService.resolve(host, path).ifPresentOrElse(
                route -> dispatchToAgent(route, request, response, asyncContext),
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

    private void dispatchToAgent(Route route, HttpServletRequest request,
                                  HttpServletResponse response, AsyncContext asyncContext) {
        try {
            String correlationId = UUID.randomUUID().toString();
            RequestPayload payload = buildPayload(route, request);
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
                        writeResponse(resp, response);
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

    private void writeResponse(ResponsePayload resp, HttpServletResponse response) throws IOException {
        response.setStatus(resp.status());
        resp.headers().forEach((name, value) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                response.setHeader(name, value);
            }
        });
        byte[] body = resp.body() != null ? Base64.getDecoder().decode(resp.body()) : new byte[0];
        response.getOutputStream().write(body);
    }

    private RequestPayload buildPayload(Route route, HttpServletRequest request) throws IOException {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(name)) {
                headers.put(name, request.getHeader(name));
            }
        }

        headers.put("x-forwarded-for", request.getRemoteAddr());
        headers.put("x-forwarded-host", request.getServerName());
        headers.put("x-forwarded-proto", request.getScheme());
        headers.put("x-real-ip", request.getRemoteAddr());

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
                route.getPathPrefix(),
                request.getRemoteAddr()
        );
    }
}
