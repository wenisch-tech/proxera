package tech.wenisch.proxera.tunnel;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends unsolicited WebSocket PONG control frames to all connected tunnel agents.
 *
 * The agent (gorilla/websocket) resets its read deadline only when it RECEIVES a
 * WebSocket protocol-level PONG control frame (via its PongHandler). Sending a PING
 * would cause the agent to send a PONG back to the server — the agent's own
 * PongHandler would never fire. Sending an unsolicited PONG is explicitly allowed
 * by RFC 6455 §5.5.3 as a unidirectional keepalive mechanism.
 */
@Component
@Slf4j
public class TunnelHeartbeat {

    /** Must be less than the agent's HeartbeatTimeout (default 10s). */
    private static final long PING_INTERVAL_MS = 5_000;
    private static final long STALE_TIMEOUT_MS = 30_000;

    private final TunnelManager tunnelManager;

    public TunnelHeartbeat(TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    @Scheduled(fixedDelay = PING_INTERVAL_MS)
    public void sendHeartbeats() {
        long now = System.currentTimeMillis();
        Map<UUID, Long> lastPongAt = tunnelManager.getLastPongAt();

        tunnelManager.getActiveSessions().forEach((agentId, session) -> {
            if (!session.isOpen()) {
                return;
            }

            Long lastPong = lastPongAt.get(agentId);
            if (lastPong != null && (now - lastPong) > STALE_TIMEOUT_MS) {
                log.warn("Agent {} has not responded to ping in {}ms — closing stale session",
                        agentId, now - lastPong);
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception e) {
                    log.debug("Error closing stale session for agent {}: {}", agentId, e.getMessage());
                }
                return;
            }

            try {
                synchronized (session) {
                    session.sendMessage(new PongMessage(ByteBuffer.allocate(0)));
                }
            } catch (Exception e) {
                log.debug("Failed to send WebSocket ping to agent {}: {}", agentId, e.getMessage());
            }
        });
    }
}
