package tech.wenisch.proxera.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.AgentService;
import tech.wenisch.proxera.service.RouteService;

@Controller
@RequestMapping("/admin/routes")
public class RouteController {

    private final RouteService routeService;
    private final AgentService agentService;
    private final AccessLogService accessLogService;

    public RouteController(RouteService routeService,
                           AgentService agentService,
                           AccessLogService accessLogService) {
        this.routeService = routeService;
        this.agentService = agentService;
        this.accessLogService = accessLogService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("routes", routeService.findAll());
        model.addAttribute("agents", agentService.findAll());
        return "admin/routes";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        return routeService.findById(id).map(route -> {
            model.addAttribute("route", route);
            model.addAttribute("recentLogs", accessLogService.getRecentForRoute(id, 50));
            return "admin/route-detail";
        }).orElse("redirect:/admin/routes");
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("route", new Route());
        model.addAttribute("agents", agentService.findAll());
        return "admin/route-form";
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam UUID agentId,
                       @RequestParam String localHost,
                       @RequestParam int localPort,
                       @RequestParam(required = false) boolean enabled,
                       @RequestParam(required = false) boolean forwardClientIpHeaders,
                       @RequestParam(required = false) List<String> domainHosts,
                       @RequestParam(required = false) List<String> domainPaths,
                       @RequestParam(required = false) List<String> domainStrips,
                       RedirectAttributes ra) {
        try {
            Agent agent = agentService.findById(agentId)
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
            Route route = Route.builder()
                    .name(name)
                    .agent(agent)
                    .localHost(localHost)
                    .localPort(localPort)
                    .enabled(enabled)
                    .forwardClientIpHeaders(forwardClientIpHeaders)
                    .build();
            addDomainEntries(route, domainHosts, domainPaths, domainStrips);
            routeService.save(route);
            ra.addFlashAttribute("success", "Route saved successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/routes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        routeService.delete(id);
        ra.addFlashAttribute("success", "Route deleted.");
        return "redirect:/admin/routes";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @RequestParam String name,
                         @RequestParam UUID agentId,
                         @RequestParam String localHost,
                         @RequestParam int localPort,
                         @RequestParam(required = false) boolean enabled,
                         @RequestParam(required = false) boolean forwardClientIpHeaders,
                         @RequestParam(required = false) List<String> domainHosts,
                         @RequestParam(required = false) List<String> domainPaths,
                         @RequestParam(required = false) List<String> domainStrips,
                         RedirectAttributes ra) {
        try {
            Route route = routeService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found"));
            Agent agent = agentService.findById(agentId)
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
            route.setName(name);
            route.setAgent(agent);
            route.setLocalHost(localHost);
            route.setLocalPort(localPort);
            route.setEnabled(enabled);
            route.setForwardClientIpHeaders(forwardClientIpHeaders);
            route.getDomains().clear();
            addDomainEntries(route, domainHosts, domainPaths, domainStrips);
            routeService.save(route);
            ra.addFlashAttribute("success", "Route updated successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/routes";
    }

    private void addDomainEntries(Route route, List<String> hosts, List<String> paths, List<String> strips) {
        if (hosts == null) return;
        for (int i = 0; i < hosts.size(); i++) {
            String host = hosts.get(i).trim().toLowerCase();
            if (host.isBlank()) continue;
            String path = (paths != null && i < paths.size()) ? paths.get(i).trim() : "";
            boolean strip = (strips != null && i < strips.size()) && "true".equals(strips.get(i));
            RouteDomain rd = new RouteDomain();
            rd.setRoute(route);
            rd.setDomain(host);
            rd.setPathPrefix(path.isBlank() ? null : path);
            rd.setStripPrefix(strip);
            route.getDomains().add(rd);
        }
    }
}
