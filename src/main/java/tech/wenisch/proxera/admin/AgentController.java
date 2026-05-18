package tech.wenisch.proxera.admin;

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
import tech.wenisch.proxera.service.AgentService;
import tech.wenisch.proxera.service.RegistrationTokenService;
import tech.wenisch.proxera.service.RouteService;

@Controller
@RequestMapping("/admin/agents")
public class AgentController {

    private final AgentService agentService;
    private final RouteService routeService;
    private final RegistrationTokenService tokenService;

    public AgentController(AgentService agentService,
                           RouteService routeService,
                           RegistrationTokenService tokenService) {
        this.agentService = agentService;
        this.routeService = routeService;
        this.tokenService = tokenService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("agents", agentService.findAll());
        return "admin/agents";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        return agentService.findById(id).map(agent -> {
            model.addAttribute("agent", agent);
            model.addAttribute("routes", routeService.findByAgentId(id));
            return "admin/agent-detail";
        }).orElse("redirect:/admin/agents");
    }

    @PostMapping
    public String create(@RequestParam String name, RedirectAttributes ra) {
        try {
            Agent agent = agentService.create(name);
            ra.addFlashAttribute("success", "Agent '" + name + "' created.");
            return "redirect:/admin/agents/" + agent.getId();
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/agents";
        }
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable UUID id, @RequestParam String name, RedirectAttributes ra) {
        try {
            agentService.rename(id, name);
            ra.addFlashAttribute("success", "Agent renamed to '" + name + "'.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/agents/" + id;
    }

    @PostMapping("/{id}/token")
    public String generateToken(@PathVariable UUID id, RedirectAttributes ra) {
        try {
            String rawToken = tokenService.generate(id);
            ra.addFlashAttribute("newToken", rawToken);
            ra.addFlashAttribute("success", "New registration token generated. Copy it now — it will not be shown again.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/agents/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        agentService.delete(id);
        ra.addFlashAttribute("success", "Agent deleted.");
        return "redirect:/admin/agents";
    }
}
