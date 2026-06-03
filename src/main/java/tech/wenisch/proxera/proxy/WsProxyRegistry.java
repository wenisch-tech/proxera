package tech.wenisch.proxera.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.WsRelayBus;
import tech.wenisch.proxera.bus.WsRelayMessage;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.TunnelFrame;
import tech.wenisch.proxera.tunnel.TunnelManager;

/**
 * Central registry for active proxied WebSocket sessions.
 *
 * Each proxied WebSocket connection has two halves:
 * <ul>
 *   <li><b>Client side</b> — the browser/client WebSocketSession that the user opened.
 *       Always on the same pod as the one that performed the HTTP upgrade.</li>
 *   <li><b>Agent side</b> — the TunnelManager session to the agent, which in turn
 *       forwards to the local LAN service.  May be on a different pod in Redis mode.</li>
 * </ul>
 *
 * {@link WsRelayBus} provides cross-pod pub/sub for the two channel directions
 * ({@code a2c}: agent-to-client, {@code c2a}: client-to-agent).
 */
@Component
@Slf4j
public class WsProxyRegistry {

    private static final int CLIENT_SEND_TIME_LIMIT_MS = 10_000;
    private static final int CLIENT_SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final WsRelayBus relayBus;
    private final TunnelManager tunnelManager;

    // Client-side state (keyed by wsSessionId)
    private final ConcurrentHashMap<String, WebSocketSession> clientSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> openFutures = new ConcurrentHashMap<>();

    // Agent-side state (keyed by wsSessionId) — only populated on the pod with the agent's tunnel
    private final ConcurrentHashMap<String, UUID> agentSessions = new ConcurrentHashMap<>();
    // Reverse map: agentId → Set<wsSessionId>, used to close all sessions on agent disconnect
    private final ConcurrentHashMap<UUID, Set<String>> agentToSessions = new ConcurrentHashMap<>();

    public WsProxyRegistry(WsRelayBus relayBus, TunnelManager tunnelManager) {
        this.relayBus = relayBus;
        this.tunnelManager = tunnelManager;
    }

    // -------------------------------------------------------------------------
    // Client-side API (called from ProxiedClientWebSocketHandler)
    // -------------------------------------------------------------------------

    /**
     * Register the client's WebSocketSession and subscribe to agent-to-client messages.
     *
     * @return a future that completes with {@code true} on WS_OPEN_ACK or {@code false}
     *         on WS_OPEN_REJECT.  A 10-second timeout is enforced by the caller.
     */
    public CompletableFuture<Boolean> registerClientSession(String wsSessionId, WebSocketSession clientSession) {
        CompletableFuture<Boolean> openFuture = new CompletableFuture<>();
        WebSocketSession safeClientSession = new ConcurrentWebSocketSessionDecorator(
                clientSession,
                CLIENT_SEND_TIME_LIMIT_MS,
                CLIENT_SEND_BUFFER_SIZE_LIMIT_BYTES);
        clientSessions.put(wsSessionId, safeClientSession);
        openFutures.put(wsSessionId, openFuture);

        relayBus.subscribeA2C(wsSessionId, msg -> {
            switch (msg.type()) {
                case "OPEN_ACK" -> openFuture.complete(true);
                case "OPEN_REJECT" -> openFuture.complete(false);
                case "DATA" -> {
                    try {
                        byte[] raw = Base64.getDecoder().decode(msg.data());
                        if (safeClientSession.isOpen()) {
                            safeClientSession.sendMessage(msg.binary()
                                    ? new BinaryMessage(raw)
                                    : new TextMessage(new String(raw, StandardCharsets.UTF_8)));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to deliver WS_DATA to client session {}: {}", wsSessionId, e.getMessage());
                    }
                }
                case "CLOSE" -> {
                    try {
                        if (safeClientSession.isOpen()) {
                            safeClientSession.close(new CloseStatus(msg.code(),
                                    msg.reason() != null ? msg.reason() : ""));
                        }
                    } catch (Exception e) {
                        log.debug("Client session {} already closed: {}", wsSessionId, e.getMessage());
                    }
                    removeClientSide(wsSessionId);
                }
                default -> log.warn("Unknown WsRelayMessage type '{}' on a2c for session {}", msg.type(), wsSessionId);
            }
        });

        return openFuture;
    }

    /** Relay a frame received from the browser client toward the agent. */
    public void publishFromClient(String wsSessionId, String base64Data, boolean binary) {
        relayBus.publishC2A(wsSessionId, WsRelayMessage.data(base64Data, binary));
    }

    /** Relay a client-initiated close toward the agent and clean up client-side state. */
    public void publishClientClose(String wsSessionId, int code, String reason) {
        relayBus.publishC2A(wsSessionId, WsRelayMessage.close(code, reason));
        removeClientSide(wsSessionId);
    }

    /** Remove all client-side state and a2c subscription for this session. */
    public void removeClientSide(String wsSessionId) {
        clientSessions.remove(wsSessionId);
        openFutures.remove(wsSessionId);
        relayBus.unsubscribe(wsSessionId);
    }

    // -------------------------------------------------------------------------
    // Agent-side API (called from TunnelWebSocketHandler)
    // -------------------------------------------------------------------------

    /**
     * Register the agent side of a proxied WebSocket session and subscribe to
     * client-to-agent relay messages.  Called when WS_OPEN_ACK is received from
     * the agent on THIS pod.
     */
    public void registerAgentSession(String wsSessionId, UUID agentId) {
        agentSessions.put(wsSessionId, agentId);
        agentToSessions.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(wsSessionId);

        relayBus.subscribeC2A(wsSessionId, msg -> {
            try {
                switch (msg.type()) {
                    case "DATA" -> {
                        Map<String, Object> payload = Map.of(
                                "wsSessionId", wsSessionId,
                                "data", msg.data(),
                                "binary", msg.binary());
                        tunnelManager.sendFrame(agentId,
                                TunnelFrame.of(FrameType.WS_DATA, wsSessionId, payload));
                    }
                    case "CLOSE" -> {
                        Map<String, Object> payload = Map.of(
                                "wsSessionId", wsSessionId,
                                "code", msg.code(),
                                "reason", msg.reason() != null ? msg.reason() : "");
                        tunnelManager.sendFrame(agentId,
                                TunnelFrame.of(FrameType.WS_CLOSE, wsSessionId, payload));
                        removeAgentSide(wsSessionId);
                    }
                    default -> log.warn("Unknown WsRelayMessage type '{}' on c2a for session {}", msg.type(), wsSessionId);
                }
            } catch (IOException e) {
                log.warn("Failed to relay c2a frame to agent {} for session {}: {}",
                        agentId, wsSessionId, e.getMessage());
            }
        });
    }

    /** Notify the client's pod that the upstream WS connection opened successfully. */
    public void publishAgentOpenAck(String wsSessionId) {
        relayBus.publishA2C(wsSessionId, WsRelayMessage.openAck());
    }

    /** Notify the client's pod that the upstream WS connection was rejected, then clean up. */
    public void publishAgentOpenReject(String wsSessionId, int code, String reason) {
        relayBus.publishA2C(wsSessionId, WsRelayMessage.openReject(code, reason));
        removeAgentSide(wsSessionId);
    }

    /** Relay a frame from the local LAN service toward the client. */
    public void publishFromAgent(String wsSessionId, String base64Data, boolean binary) {
        relayBus.publishA2C(wsSessionId, WsRelayMessage.data(base64Data, binary));
    }

    /** Relay an agent-side close toward the client and clean up agent-side state. */
    public void publishAgentClose(String wsSessionId, int code, String reason) {
        relayBus.publishA2C(wsSessionId, WsRelayMessage.close(code, reason));
        removeAgentSide(wsSessionId);
    }

    /**
     * Close all proxied WebSocket sessions belonging to an agent that disconnected.
     * Called from {@link tech.wenisch.proxera.tunnel.TunnelWebSocketHandler} on agent disconnect.
     */
    public void closeAllForAgent(UUID agentId) {
        Set<String> sessions = agentToSessions.remove(agentId);
        if (sessions == null) return;
        for (String wsSessionId : new HashSet<>(sessions)) {
            log.debug("Closing proxied WS session {} because agent {} disconnected", wsSessionId, agentId);
            relayBus.publishA2C(wsSessionId, WsRelayMessage.close(
                    CloseStatus.SERVICE_RESTARTED.getCode(), "Agent disconnected"));
            removeAgentSide(wsSessionId);
        }
    }

    private void removeAgentSide(String wsSessionId) {
        UUID agentId = agentSessions.remove(wsSessionId);
        if (agentId != null) {
            Set<String> sessions = agentToSessions.get(agentId);
            if (sessions != null) sessions.remove(wsSessionId);
        }
        relayBus.unsubscribe(wsSessionId);
    }
}
