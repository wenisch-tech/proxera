package tech.wenisch.proxera.tunnel;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.TopologyEvent;
import tech.wenisch.proxera.service.ClientService;

@Component
@Slf4j
public class TunnelWebSocketHandler extends TextWebSocketHandler {

    private final TunnelManager tunnelManager;
    private final MessageBus messageBus;
    private final ClientService clientService;
    private final ObjectMapper objectMapper;

    public TunnelWebSocketHandler(TunnelManager tunnelManager,
                                   MessageBus messageBus,
                                   ClientService clientService,
                                   ObjectMapper objectMapper) {
        this.tunnelManager = tunnelManager;
        this.messageBus = messageBus;
        this.clientService = clientService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID clientId = (UUID) session.getAttributes().get("clientId");
        String clientName = (String) session.getAttributes().get("clientName");
        if (clientId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        tunnelManager.register(clientId, session);
        session.setTextMessageSizeLimit(64 * 1024 * 1024);
        clientService.markConnected(clientId);
        messageBus.publishTopology(new TopologyEvent("CLIENT_CONNECTED", clientId.toString(), clientName));

        // Send REGISTER_ACK
        TunnelFrame ack = TunnelFrame.of(FrameType.REGISTER_ACK, UUID.randomUUID().toString(),
                Map.of("clientId", clientId.toString(), "name", clientName));
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));

        log.info("Client connected: {} ({})", clientName, clientId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TunnelFrame frame = objectMapper.readValue(message.getPayload(), TunnelFrame.class);
        UUID clientId = (UUID) session.getAttributes().get("clientId");

        switch (FrameType.valueOf(frame.type())) {
            case RESPONSE -> {
                ResponsePayload response = objectMapper.convertValue(frame.payload(), ResponsePayload.class);
                messageBus.complete(frame.correlationId(), response);
            }
            case PING -> {
                TunnelFrame pong = TunnelFrame.pong(frame.correlationId());
                synchronized (session) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pong)));
                }
            }
            case PONG -> tunnelManager.recordPong(clientId);
            default -> log.warn("Unexpected frame type from client {}: {}", clientId, frame.type());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID clientId = (UUID) session.getAttributes().get("clientId");
        String clientName = (String) session.getAttributes().getOrDefault("clientName", "unknown");
        if (clientId != null) {
            tunnelManager.unregister(clientId);
            clientService.markDisconnected(clientId);
            messageBus.publishTopology(new TopologyEvent("CLIENT_DISCONNECTED", clientId.toString(), clientName));
            log.info("Client disconnected: {} ({}) — {}", clientName, clientId, status);
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, org.springframework.web.socket.PongMessage message) {
        UUID clientId = (UUID) session.getAttributes().get("clientId");
        if (clientId != null) {
            tunnelManager.recordPong(clientId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }
}
