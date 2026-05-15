package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.domain.Client;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.ClientService;
import tech.wenisch.proxera.service.RouteService;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin/routes")
public class RouteController {

    private final RouteService routeService;
    private final ClientService clientService;
    private final AccessLogService accessLogService;

    public RouteController(RouteService routeService,
                           ClientService clientService,
                           AccessLogService accessLogService) {
        this.routeService = routeService;
        this.clientService = clientService;
        this.accessLogService = accessLogService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("routes", routeService.findAll());
        model.addAttribute("clients", clientService.findAll());
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
        model.addAttribute("clients", clientService.findAll());
        return "admin/route-form";
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam UUID clientId,
                       @RequestParam String localHost,
                       @RequestParam int localPort,
                       @RequestParam(required = false) String pathPrefix,
                       @RequestParam(required = false) boolean stripPrefix,
                       @RequestParam(required = false) boolean enabled,
                       @RequestParam(required = false) List<String> domains,
                       RedirectAttributes ra) {
        try {
            Client client = clientService.findById(clientId)
                    .orElseThrow(() -> new IllegalArgumentException("Client not found"));
            Route route = Route.builder()
                    .name(name)
                    .client(client)
                    .localHost(localHost)
                    .localPort(localPort)
                    .pathPrefix(pathPrefix != null && !pathPrefix.isBlank() ? pathPrefix : null)
                    .stripPrefix(stripPrefix)
                    .enabled(enabled)
                    .build();
            if (domains != null) {
                for (String domain : domains) {
                    String trimmed = domain.trim().toLowerCase();
                    if (!trimmed.isBlank()) {
                        RouteDomain rd = new RouteDomain();
                        rd.setRoute(route);
                        rd.setDomain(trimmed);
                        route.getDomains().add(rd);
                    }
                }
            }
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
                         @RequestParam UUID clientId,
                         @RequestParam String localHost,
                         @RequestParam int localPort,
                         @RequestParam(required = false) String pathPrefix,
                         @RequestParam(required = false) boolean stripPrefix,
                         @RequestParam(required = false) boolean enabled,
                         @RequestParam(required = false) List<String> domains,
                         RedirectAttributes ra) {
        try {
            Route route = routeService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found"));
            Client client = clientService.findById(clientId)
                    .orElseThrow(() -> new IllegalArgumentException("Client not found"));
            route.setName(name);
            route.setClient(client);
            route.setLocalHost(localHost);
            route.setLocalPort(localPort);
            route.setPathPrefix(pathPrefix != null && !pathPrefix.isBlank() ? pathPrefix : null);
            route.setStripPrefix(stripPrefix);
            route.setEnabled(enabled);
            route.getDomains().clear();
            if (domains != null) {
                for (String domain : domains) {
                    String trimmed = domain.trim().toLowerCase();
                    if (!trimmed.isBlank()) {
                        RouteDomain rd = new RouteDomain();
                        rd.setRoute(route);
                        rd.setDomain(trimmed);
                        route.getDomains().add(rd);
                    }
                }
            }
            routeService.save(route);
            ra.addFlashAttribute("success", "Route updated successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/routes";
    }
}
