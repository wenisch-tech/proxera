package tech.wenisch.proxera.tunnel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/**
 * Sends WebSocket PING control frames to all connected tunnel clients and closes
 * sessions that have not responded within the stale timeout.
 *
 * The client (gorilla/websocket) resets its read deadline only when it receives
 * a WebSocket protocol-level PONG control frame — NOT application-level JSON frames.
 * Sending PingMessage causes gorilla's default PingHandler to reply with a PONG
 * control frame, which triggers the client's PongHandler to extend its read deadline.
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
    public void sendPings() {
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
                    session.sendMessage(new PingMessage(ByteBuffer.allocate(0)));
                }
            } catch (Exception e) {
                log.debug("Failed to send WebSocket ping to client {}: {}", clientId, e.getMessage());
            }
        });
    }
}
