package tech.wenisch.proxera.tunnel;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class TunnelManager {

    private final ConcurrentHashMap<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastPongAt = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public TunnelManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID agentId, WebSocketSession session) {
        sessions.put(agentId, session);
        lastPongAt.put(agentId, System.currentTimeMillis());
    }

    public void unregister(UUID agentId) {
        sessions.remove(agentId);
        lastPongAt.remove(agentId);
    }

    public boolean isConnected(UUID agentId) {
        WebSocketSession session = sessions.get(agentId);
        return session != null && session.isOpen();
    }

    public void recordPong(UUID agentId) {
        lastPongAt.put(agentId, System.currentTimeMillis());
    }

    public void sendFrame(UUID agentId, TunnelFrame frame) throws IOException {
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) {
            throw new IOException("No open session for agent: " + agentId);
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
