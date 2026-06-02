package tech.wenisch.proxera.proxy;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.service.RoutingService;

/**
 * Handles HTTP→WebSocket upgrade for proxied WebSocket connections.
 *
 * When {@link ProxyController} detects an {@code Upgrade: websocket} header it
 * delegates here instead of starting an async context.
 *
 * Responsibilities:
 * <ol>
 *   <li>Resolve the route for the incoming request's host/path.</li>
 *   <li>Build a {@code WS_OPEN} payload (same header-forwarding rules as
 *       {@link ProxyService#buildPayload}).</li>
 *   <li>Create a {@link ProxiedClientWebSocketHandler} and perform the HTTP→WS
 *       upgrade via Spring's {@link DefaultHandshakeHandler}.</li>
 * </ol>
 */
@Service
@Slf4j
public class WebSocketProxyService {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade"
    );
    private static final Set<String> CLIENT_IP_HEADERS = Set.of(
            "forwarded", "x-forwarded", "x-forwarded-for", "x-real-ip",
            "x-client-ip", "x-cluster-client-ip"
    );

    private final RoutingService routingService;
    private final MessageBus messageBus;
    private final WsProxyRegistry wsProxyRegistry;
    private final DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();

    public WebSocketProxyService(RoutingService routingService,
                                  MessageBus messageBus,
                                  WsProxyRegistry wsProxyRegistry) {
        this.routingService = routingService;
        this.messageBus = messageBus;
        this.wsProxyRegistry = wsProxyRegistry;
    }

    /**
     * Perform the WebSocket upgrade and start the proxied session.
     *
     * @return {@code true} if the upgrade was initiated, {@code false} if no route was found
     *         (caller should send a 404 in that case)
     */
    public boolean handleUpgrade(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String host = request.getHeader("Host");
        String path = request.getRequestURI();

        RouteDomain routeDomain = routingService.resolve(host, path).orElse(null);
        if (routeDomain == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        Route route = routeDomain.getRoute();
        UUID agentId = route.getAgent().getId();
        String wsSessionId = UUID.randomUUID().toString();

        Map<String, Object> wsOpenPayload = buildWsOpenPayload(wsSessionId, routeDomain, request);

        ProxiedClientWebSocketHandler handler = new ProxiedClientWebSocketHandler(
                wsSessionId, agentId, wsOpenPayload, messageBus, wsProxyRegistry);

        ServerHttpRequest serverRequest = new ServletServerHttpRequest(request);
        ServerHttpResponse serverResponse = new ServletServerHttpResponse(response);

        try {
            handshakeHandler.doHandshake(serverRequest, serverResponse, handler, Map.of());
        } catch (Exception e) {
            log.warn("WebSocket handshake failed for session {}: {}", wsSessionId, e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "WebSocket upgrade failed");
        }

        return true;
    }

    private Map<String, Object> buildWsOpenPayload(String wsSessionId, RouteDomain routeDomain,
                                                    HttpServletRequest request) {
        Route route = routeDomain.getRoute();

        // Copy non-hop-by-hop headers
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(name)
                    && (route.isForwardClientIpHeaders() || !CLIENT_IP_HEADERS.contains(name))) {
                headers.put(name, request.getHeader(name));
            }
        }

        String clientIp = request.getRemoteAddr();
        if (route.isForwardClientIpHeaders()) {
            // Apply the same X-Forwarded-* header logic as ProxyService.buildPayload()
            String existingXff = headers.get("x-forwarded-for");
            headers.put("x-forwarded-for",
                    existingXff != null && !existingXff.isBlank()
                            ? existingXff + ", " + clientIp : clientIp);
            headers.put("x-real-ip", clientIp);
        }
        String hostHeader = request.getHeader("Host");
        headers.put("x-forwarded-host", hostHeader != null ? hostHeader : request.getServerName());
        headers.put("x-forwarded-proto", request.getScheme());
        headers.put("x-forwarded-port", String.valueOf(request.getServerPort()));

        // Determine the effective path after optional prefix stripping
        String effectivePath = request.getRequestURI();
        String stripPrefix = routeDomain.isStripPrefix() ? routeDomain.getPathPrefix() : null;
        if (stripPrefix != null && effectivePath.startsWith(stripPrefix)) {
            effectivePath = effectivePath.substring(stripPrefix.length());
            if (effectivePath.isEmpty()) effectivePath = "/";
        }

        return Map.of(
                "wsSessionId", wsSessionId,
                "localHost", route.getLocalHost(),
                "localPort", route.getLocalPort(),
                "path", effectivePath,
                "queryString", request.getQueryString() != null ? request.getQueryString() : "",
                "headers", headers
        );
    }
}
