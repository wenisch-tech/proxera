package tech.wenisch.proxera.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class TunnelManager {

    private final ConcurrentHashMap<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastPongAt = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public TunnelManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID clientId, WebSocketSession session) {
        sessions.put(clientId, session);
        lastPongAt.put(clientId, System.currentTimeMillis());
    }

    public void unregister(UUID clientId) {
        sessions.remove(clientId);
        lastPongAt.remove(clientId);
    }

    public boolean isConnected(UUID clientId) {
        WebSocketSession session = sessions.get(clientId);
        return session != null && session.isOpen();
    }

    public void recordPong(UUID clientId) {
        lastPongAt.put(clientId, System.currentTimeMillis());
    }

    public void sendFrame(UUID clientId, TunnelFrame frame) throws IOException {
        WebSocketSession session = sessions.get(clientId);
        if (session == null || !session.isOpen()) {
            throw new IOException("No open session for client: " + clientId);
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        }
    }

    public Map<UUID, WebSocketSession> getActiveSessions() {
        return Map.copyOf(sessions);
    }

    public Map<UUID, Long> getLastPongAt() {
        return Map.copyOf(lastPongAt);
    }
}
