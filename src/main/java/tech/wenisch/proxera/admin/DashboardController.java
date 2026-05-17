package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.AgentService;
import tech.wenisch.proxera.service.RouteService;
import tech.wenisch.proxera.tunnel.TunnelManager;

@Controller
@RequestMapping("/admin")
public class DashboardController {

    private final AgentService agentService;
    private final RouteService routeService;
    private final AccessLogService accessLogService;
    private final TunnelManager tunnelManager;

    public DashboardController(AgentService agentService,
                               RouteService routeService,
                               AccessLogService accessLogService,
                               TunnelManager tunnelManager) {
        this.agentService = agentService;
        this.routeService = routeService;
        this.accessLogService = accessLogService;
        this.tunnelManager = tunnelManager;
    }

    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        return "redirect:/admin/topology";
    }
}
