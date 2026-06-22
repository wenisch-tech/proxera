package tech.wenisch.proxera.tunnel;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.proxy.WsProxyRegistry;
import tech.wenisch.proxera.service.AgentService;

class TunnelWebSocketHandlerTest {

    @Test
    void errorFrameRoutesToMessageBusFailure() throws Exception {
        TunnelManager tunnelManager = mock(TunnelManager.class);
        MessageBus messageBus = mock(MessageBus.class);
        AgentService agentService = mock(AgentService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        WsProxyRegistry wsProxyRegistry = mock(WsProxyRegistry.class);
        ExposedTunnelWebSocketHandler handler = new ExposedTunnelWebSocketHandler(
                tunnelManager, messageBus, agentService, objectMapper, wsProxyRegistry);
        WebSocketSession session = mock(WebSocketSession.class);
        UUID agentId = UUID.randomUUID();
        when(session.getAttributes()).thenReturn(Map.of("agentId", agentId, "agentName", "k8s@home"));

        TunnelFrame frame = TunnelFrame.of(FrameType.ERROR, "corr-1",
                Map.of("code", "UPSTREAM_ERROR", "message", "backend failed"));

        handler.accept(session, new TextMessage(objectMapper.writeValueAsString(frame)));

        verify(messageBus).fail(eq("corr-1"), eq(new TunnelErrorPayload("UPSTREAM_ERROR", "backend failed")));
    }

    private static final class ExposedTunnelWebSocketHandler extends TunnelWebSocketHandler {

        private ExposedTunnelWebSocketHandler(TunnelManager tunnelManager,
                                              MessageBus messageBus,
                                              AgentService agentService,
                                              ObjectMapper objectMapper,
                                              WsProxyRegistry wsProxyRegistry) {
            super(tunnelManager, messageBus, agentService, objectMapper, wsProxyRegistry);
        }

        private void accept(WebSocketSession session, TextMessage message) throws Exception {
            handleTextMessage(session, message);
        }
    }
}
