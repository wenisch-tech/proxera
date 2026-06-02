package tech.wenisch.proxera.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;
import tech.wenisch.proxera.service.AgentService;
import tech.wenisch.proxera.service.IngressService;
import tech.wenisch.proxera.service.RouteService;
import tech.wenisch.proxera.tunnel.TunnelManager;

class TopologyControllerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final RouteService routeService = mock(RouteService.class);
    private final TunnelManager tunnelManager = mock(TunnelManager.class);
    private final IngressService ingressService = mock(IngressService.class);
    private final TopologyController controller = new TopologyController(
            agentService, routeService, tunnelManager, ingressService);

    @Test
    @SuppressWarnings("unchecked")
    void connectedReflectsDatabaseStatusNotOnlyThisPodTunnelOwnership() {
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder()
                .id(agentId)
                .name("k8s@home")
                .status(AgentStatus.CONNECTED)
                .connectedPodId("proxera-other-pod")
                .build();

        when(agentService.findAll()).thenReturn(List.of(agent));
        when(routeService.findByAgentIdWithDomains(agentId)).thenReturn(List.of());
        when(tunnelManager.isConnected(agentId)).thenReturn(false);
        when(ingressService.isAvailable()).thenReturn(false);

        Map<String, Object> body = controller.topologyData().getBody();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        Map<String, Object> agentNode = nodes.stream()
                .filter(node -> agentId.toString().equals(node.get("id")))
                .findFirst()
                .orElseThrow();

        assertThat(agentNode.get("connected")).isEqualTo(true);
        assertThat(agentNode.get("databaseStatus")).isEqualTo("CONNECTED");
        assertThat(agentNode.get("localTunnelConnected")).isEqualTo(false);
        assertThat(agentNode.get("connectedPodId")).isEqualTo("proxera-other-pod");
    }
}
