package tech.wenisch.proxera.admin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import tech.wenisch.proxera.config.PodIdentityResolver;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;
import tech.wenisch.proxera.domain.IngressSpec;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.service.AgentService;
import tech.wenisch.proxera.service.IngressService;
import tech.wenisch.proxera.service.RouteService;
import tech.wenisch.proxera.tunnel.TunnelManager;

@Controller
@RequestMapping("/admin/topology")
public class TopologyController {

    private final AgentService agentService;
    private final RouteService routeService;
    private final TunnelManager tunnelManager;
    private final IngressService ingressService;
    private final PodIdentityResolver podIdentityResolver;

    public TopologyController(AgentService agentService,
                              RouteService routeService,
                              TunnelManager tunnelManager,
                              IngressService ingressService,
                              PodIdentityResolver podIdentityResolver) {
        this.agentService = agentService;
        this.routeService = routeService;
        this.tunnelManager = tunnelManager;
        this.ingressService = ingressService;
        this.podIdentityResolver = podIdentityResolver;
    }

    @GetMapping
    public String topology() {
        return "admin/topology";
    }

    /**
     * Returns the current topology as JSON for the D3.js graph.
     * Format: { nodes: [...], links: [...] }
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> topologyData() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();

        // Add server (this pod) as a node
        String podId = podIdentityResolver.currentPodId().orElse("server");
        Map<String, Object> serverNode = new HashMap<>();
        serverNode.put("id", podId);
        serverNode.put("type", "server");
        serverNode.put("name", "Proxera Server");
        serverNode.put("internalIp", getServerLocalIp());
        String externalIp = System.getenv("EXTERNAL_IP");
        if (externalIp != null && !externalIp.isBlank()) serverNode.put("externalIp", externalIp);
        nodes.add(serverNode);

        for (Agent agent : agentService.findAll()) {
            boolean localTunnelConnected = tunnelManager.isConnected(agent.getId());
            boolean databaseConnected = agent.getStatus() == AgentStatus.CONNECTED;
            boolean topologyConnected = databaseConnected || localTunnelConnected;
            Map<String, Object> node = new HashMap<>();
            node.put("id", agent.getId().toString());
            node.put("type", "agent");
            node.put("name", agent.getName());
            node.put("status", agent.getStatus().name());
            node.put("connected", databaseConnected);
            node.put("databaseStatus", agent.getStatus().name());
            node.put("localTunnelConnected", localTunnelConnected);
            if (agent.getConnectedPodId() != null) node.put("connectedPodId", agent.getConnectedPodId());
            if (agent.getRemoteIp() != null) node.put("remoteIp", agent.getRemoteIp());
            nodes.add(node);

            // Render cluster-level tunnel ownership, not only the session attached
            // to this exact JVM, so multi-pod deployments show all connected agents.
            if (topologyConnected) {
                links.add(Map.of("source", podId, "target", agent.getId().toString(), "type", "tunnel"));
            }

            for (Route route : routeService.findByAgentIdWithDomains(agent.getId())) {
                Map<String, Object> routeNode = new HashMap<>();
                routeNode.put("id", route.getId().toString());
                routeNode.put("type", "route");
                routeNode.put("name", route.getName());
                routeNode.put("enabled", route.isEnabled());
                routeNode.put("target", route.getLocalHost() + ":" + route.getLocalPort());
                routeNode.put("domains", route.getDomains().stream()
                        .map(d -> d.getDomain())
                        .collect(java.util.stream.Collectors.toList()));
                nodes.add(routeNode);
                links.add(Map.of("source", agent.getId().toString(), "target", route.getId().toString(), "type", "route"));
            }
        }

        // Add ingress nodes — from K8s API when running in-cluster, unavailable placeholder otherwise
        if (ingressService.isAvailable()) {
            for (IngressSpec ingress : ingressService.listIngresses()) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", "ingress-" + ingress.getName());
                node.put("type", "ingress");
                node.put("name", ingress.getName());
                node.put("className", ingress.getClassName());
                node.put("host", ingress.getHost());
                node.put("annotations", ingress.getAnnotations());
                node.put("path", ingress.getPath());
                node.put("pathType", ingress.getPathType());
                node.put("tlsEnabled", ingress.isTlsEnabled());
                node.put("tlsSecretName", ingress.getTlsSecretName());
                nodes.add(node);
                links.add(Map.of("source", "ingress-" + ingress.getName(), "target", podId, "type", "ingress"));
            }
        } else {
            Map<String, Object> unavailableNode = new HashMap<>();
            unavailableNode.put("id", "ingress-unavailable");
            unavailableNode.put("type", "ingress-unavailable");
            unavailableNode.put("name", "Ingress");
            nodes.add(unavailableNode);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        result.put("kubernetesAvailable", ingressService.isAvailable());
        return ResponseEntity.ok(result);
    }

    private String getServerLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
