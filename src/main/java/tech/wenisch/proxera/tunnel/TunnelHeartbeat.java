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
 * Sends unsolicited WebSocket PONG control frames to all connected tunnel clients.
 *
 * The client (gorilla/websocket) resets its read deadline only when it RECEIVES a
 * WebSocket protocol-level PONG control frame (via its PongHandler). Sending a PING
 * would cause the client to send a PONG back to the server — the client's own
 * PongHandler would never fire. Sending an unsolicited PONG is explicitly allowed
 * by RFC 6455 §5.5.3 as a unidirectional keepalive mechanism.
 */
@Component
@Slf4j
public class TunnelHeartbeat {

    /** Must be less than the client's HeartbeatTimeout (default 10s). */
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

        tunnelManager.getActiveSessions().forEach((clientId, session) -> {
            if (!session.isOpen()) {
                return;
            }

            Long lastPong = lastPongAt.get(clientId);
            if (lastPong != null && (now - lastPong) > STALE_TIMEOUT_MS) {
                log.warn("Client {} has not responded to ping in {}ms — closing stale session",
                        clientId, now - lastPong);
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception e) {
                    log.debug("Error closing stale session for client {}: {}", clientId, e.getMessage());
                }
                return;
            }

            try {
                synchronized (session) {
                    session.sendMessage(new PongMessage(ByteBuffer.allocate(0)));
                }
            } catch (Exception e) {
                log.debug("Failed to send WebSocket ping to client {}: {}", clientId, e.getMessage());
            }
        });
    }
}
