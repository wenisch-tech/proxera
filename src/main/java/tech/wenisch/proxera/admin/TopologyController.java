package tech.wenisch.proxera.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import tech.wenisch.proxera.domain.Client;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.service.ClientService;
import tech.wenisch.proxera.service.RouteService;
import tech.wenisch.proxera.tunnel.TunnelManager;

@Controller
@RequestMapping("/admin/topology")
public class TopologyController {

    private final ClientService clientService;
    private final RouteService routeService;
    private final TunnelManager tunnelManager;

    public TopologyController(ClientService clientService,
                              RouteService routeService,
                              TunnelManager tunnelManager) {
        this.clientService = clientService;
        this.routeService = routeService;
        this.tunnelManager = tunnelManager;
    }

    @GetMapping
    public String topology() {
        return "redirect:/admin/";
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
        String podId = System.getenv().getOrDefault("HOSTNAME", "server");
        nodes.add(Map.of("id", podId, "type", "server", "name", "Proxera Server"));

        for (Client client : clientService.findAll()) {
            boolean connected = tunnelManager.isConnected(client.getId());
            Map<String, Object> node = new HashMap<>();
            node.put("id", client.getId().toString());
            node.put("type", "client");
            node.put("name", client.getName());
            node.put("status", client.getStatus().name());
            node.put("connected", connected);
            nodes.add(node);

            if (connected) {
                links.add(Map.of("source", podId, "target", client.getId().toString(), "type", "tunnel"));
            }

            for (Route route : routeService.findByClientId(client.getId())) {
                Map<String, Object> routeNode = new HashMap<>();
                routeNode.put("id", route.getId().toString());
                routeNode.put("type", "route");
                routeNode.put("name", route.getName());
                routeNode.put("enabled", route.isEnabled());
                routeNode.put("target", route.getLocalHost() + ":" + route.getLocalPort());
                nodes.add(routeNode);
                links.add(Map.of("source", client.getId().toString(), "target", route.getId().toString(), "type", "route"));
            }
        }

        return ResponseEntity.ok(Map.of("nodes", nodes, "links", links));
    }
}
