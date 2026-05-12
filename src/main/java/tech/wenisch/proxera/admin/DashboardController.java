package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.ClientService;
import tech.wenisch.proxera.service.RouteService;
import tech.wenisch.proxera.tunnel.TunnelManager;

@Controller
@RequestMapping("/admin")
public class DashboardController {

    private final ClientService clientService;
    private final RouteService routeService;
    private final AccessLogService accessLogService;
    private final TunnelManager tunnelManager;

    public DashboardController(ClientService clientService,
                               RouteService routeService,
                               AccessLogService accessLogService,
                               TunnelManager tunnelManager) {
        this.clientService = clientService;
        this.routeService = routeService;
        this.accessLogService = accessLogService;
        this.tunnelManager = tunnelManager;
    }

    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        model.addAttribute("connectedClients", clientService.countConnected());
        model.addAttribute("activeRoutes", routeService.countEnabled());
        model.addAttribute("activeSessions", tunnelManager.getActiveSessions().size());
        model.addAttribute("recentLogs", accessLogService.getRecentGlobal(20));
        return "admin/dashboard";
    }
}
