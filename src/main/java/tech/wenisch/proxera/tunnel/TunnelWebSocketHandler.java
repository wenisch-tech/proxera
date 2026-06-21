package tech.wenisch.proxera.tunnel;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.TopologyEvent;
import tech.wenisch.proxera.proxy.WsProxyRegistry;
import tech.wenisch.proxera.service.AgentService;

@Component
@Slf4j
public class TunnelWebSocketHandler extends TextWebSocketHandler {

    private final TunnelManager tunnelManager;
    private final MessageBus messageBus;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final WsProxyRegistry wsProxyRegistry;

    public TunnelWebSocketHandler(TunnelManager tunnelManager,
                                   MessageBus messageBus,
                                   AgentService agentService,
                                   ObjectMapper objectMapper,
                                   WsProxyRegistry wsProxyRegistry) {
        this.tunnelManager = tunnelManager;
        this.messageBus = messageBus;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.wsProxyRegistry = wsProxyRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID agentId = (UUID) session.getAttributes().get("agentId");
        String agentName = (String) session.getAttributes().get("agentName");
        if (agentId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        tunnelManager.register(agentId, session);
        messageBus.onAgentConnected(agentId);
        session.setTextMessageSizeLimit(64 * 1024 * 1024);
        String remoteIp = null;
        java.net.InetSocketAddress ra = session.getRemoteAddress();
        if (ra != null) {
            java.net.InetAddress addr = ra.getAddress();
            if (addr != null) remoteIp = addr.getHostAddress();
        }
        agentService.markConnected(agentId, remoteIp);
        messageBus.publishTopology(new TopologyEvent("AGENT_CONNECTED", agentId.toString(), agentName));

        // Send REGISTER_ACK
        TunnelFrame ack = TunnelFrame.of(FrameType.REGISTER_ACK, UUID.randomUUID().toString(),
                Map.of("agentId", agentId.toString(), "name", agentName));
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));

        log.info("Agent connected: {} ({})", agentName, agentId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TunnelFrame frame = objectMapper.readValue(message.getPayload(), TunnelFrame.class);
        UUID agentId = (UUID) session.getAttributes().get("agentId");

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
            case PONG -> tunnelManager.recordPong(agentId);
            case WS_OPEN_ACK -> {
                String wsSessionId = frame.correlationId();
                // Subscribe to c2a on this pod, then notify the client's pod via a2c
                wsProxyRegistry.registerAgentSession(wsSessionId, agentId);
                wsProxyRegistry.publishAgentOpenAck(wsSessionId);
            }
            case WS_OPEN_REJECT -> {
                String wsSessionId = frame.correlationId();
                int code = frame.payload().containsKey("code")
                        ? ((Number) frame.payload().get("code")).intValue() : 1011;
                String reason = (String) frame.payload().getOrDefault("reason", "Upstream rejected");
                wsProxyRegistry.publishAgentOpenReject(wsSessionId, code, reason);
            }
            case WS_DATA -> {
                String wsSessionId = (String) frame.payload().get("wsSessionId");
                String data = (String) frame.payload().get("data");
                boolean binary = Boolean.TRUE.equals(frame.payload().get("binary"));
                wsProxyRegistry.publishFromAgent(wsSessionId, data, binary);
            }
            case WS_CLOSE -> {
                String wsSessionId = (String) frame.payload().get("wsSessionId");
                int code = frame.payload().containsKey("code")
                        ? ((Number) frame.payload().get("code")).intValue() : 1000;
                String reason = (String) frame.payload().getOrDefault("reason", "");
                wsProxyRegistry.publishAgentClose(wsSessionId, code, reason);
            }
            default -> log.warn("Unexpected frame type from agent {}: {}", agentId, frame.type());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID agentId = (UUID) session.getAttributes().get("agentId");
        String agentName = (String) session.getAttributes().getOrDefault("agentName", "unknown");
        if (agentId != null) {
            wsProxyRegistry.closeAllForAgent(agentId);
            messageBus.onAgentDisconnected(agentId);
            tunnelManager.unregister(agentId);
            agentService.markDisconnected(agentId);
            messageBus.publishTopology(new TopologyEvent("AGENT_DISCONNECTED", agentId.toString(), agentName));
            log.info("Agent disconnected: {} ({}) — {}", agentName, agentId, status);
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, org.springframework.web.socket.PongMessage message) {
        UUID agentId = (UUID) session.getAttributes().get("agentId");
        if (agentId != null) {
            tunnelManager.recordPong(agentId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }
}
