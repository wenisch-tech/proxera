package tech.wenisch.proxera.proxy;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.TunnelFrame;

/**
 * Per-connection WebSocket handler that bridges one client WebSocket session to a
 * remote agent through the Proxera tunnel.
 *
 * Lifecycle:
 * <ol>
 *   <li>Created by {@link WebSocketProxyService} during the HTTP→WS upgrade.</li>
 *   <li>{@link #afterConnectionEstablished} registers the client session in
 *       {@link WsProxyRegistry} and sends {@code WS_OPEN} to the agent via
 *       {@link MessageBus#sendToAgent} (multi-pod safe).</li>
 *   <li>All subsequent client frames are relayed through {@link WsProxyRegistry}
 *       once the {@code WS_OPEN_ACK} is received.</li>
 *   <li>On close or transport error, {@link WsProxyRegistry#publishClientClose} is
 *       called so the agent side can tear down the local WebSocket connection.</li>
 * </ol>
 *
 * Not a Spring bean — one instance is created per proxied WebSocket connection.
 */
@Slf4j
public class ProxiedClientWebSocketHandler extends AbstractWebSocketHandler {

    private static final long OPEN_TIMEOUT_SECONDS = 10;

    private final String wsSessionId;
    private final UUID agentId;
    private final Map<String, Object> wsOpenPayload;
    private final MessageBus messageBus;
    private final WsProxyRegistry wsProxyRegistry;

    /** Set to true once WS_OPEN_ACK is received; guards frame relay. */
    private final AtomicBoolean opened = new AtomicBoolean(false);

    public ProxiedClientWebSocketHandler(String wsSessionId,
                                          UUID agentId,
                                          Map<String, Object> wsOpenPayload,
                                          MessageBus messageBus,
                                          WsProxyRegistry wsProxyRegistry) {
        this.wsSessionId = wsSessionId;
        this.agentId = agentId;
        this.wsOpenPayload = wsOpenPayload;
        this.messageBus = messageBus;
        this.wsProxyRegistry = wsProxyRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        CompletableFuture<Boolean> openFuture = wsProxyRegistry.registerClientSession(wsSessionId, session);

        // Route WS_OPEN through MessageBus so it works in both single-pod and Redis multi-pod mode
        try {
            messageBus.sendToAgent(agentId, TunnelFrame.of(FrameType.WS_OPEN, wsSessionId, wsOpenPayload));
        } catch (IOException e) {
            log.warn("Failed to send WS_OPEN to agent {} for session {}: {}", agentId, wsSessionId, e.getMessage());
            openFuture.complete(false);
        }

        openFuture.orTimeout(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ok, ex) -> {
            if (ex != null || !Boolean.TRUE.equals(ok)) {
                log.warn("WS_OPEN_ACK not received within {}s for session {} (agent {}): {}",
                        OPEN_TIMEOUT_SECONDS, wsSessionId, agentId,
                        ex != null ? ex.getMessage() : "rejected");
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.SERVER_ERROR);
                    }
                } catch (IOException closeEx) {
                    log.debug("Failed to close client session {}: {}", wsSessionId, closeEx.getMessage());
                }
                wsProxyRegistry.removeClientSide(wsSessionId);
            } else {
                opened.set(true);
                log.debug("Proxied WebSocket session {} established to agent {}", wsSessionId, agentId);
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!opened.get()) return;
        String base64 = Base64.getEncoder().encodeToString(message.getPayload().getBytes());
        wsProxyRegistry.publishFromClient(wsSessionId, base64, false);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        if (!opened.get()) return;
        String base64 = Base64.getEncoder().encodeToString(message.getPayload().array());
        wsProxyRegistry.publishFromClient(wsSessionId, base64, true);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("Proxied WebSocket session {} closed by client: {}", wsSessionId, status);
        wsProxyRegistry.publishClientClose(wsSessionId, status.getCode(),
                status.getReason() != null ? status.getReason() : "");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error on proxied session {}: {}", wsSessionId, exception.getMessage());
        wsProxyRegistry.publishClientClose(wsSessionId,
                CloseStatus.SERVER_ERROR.getCode(), "Transport error");
    }
}
