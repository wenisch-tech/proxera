package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.domain.Client;
import tech.wenisch.proxera.service.ClientService;
import tech.wenisch.proxera.service.RegistrationTokenService;
import tech.wenisch.proxera.service.RouteService;

import java.util.UUID;

@Controller
@RequestMapping("/admin/clients")
public class ClientController {

    private final ClientService clientService;
    private final RouteService routeService;
    private final RegistrationTokenService tokenService;

    public ClientController(ClientService clientService,
                            RouteService routeService,
                            RegistrationTokenService tokenService) {
        this.clientService = clientService;
        this.routeService = routeService;
        this.tokenService = tokenService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("clients", clientService.findAll());
        return "admin/clients";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        return clientService.findById(id).map(client -> {
            model.addAttribute("client", client);
            model.addAttribute("routes", routeService.findByClientId(id));
            return "admin/client-detail";
        }).orElse("redirect:/admin/clients");
    }

    @PostMapping
    public String create(@RequestParam String name, RedirectAttributes ra) {
        try {
            Client client = clientService.create(name);
            ra.addFlashAttribute("success", "Client '" + name + "' created.");
            return "redirect:/admin/clients/" + client.getId();
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/clients";
        }
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
        return "redirect:/admin/clients/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        clientService.delete(id);
        ra.addFlashAttribute("success", "Client deleted.");
        return "redirect:/admin/clients";
    }
}
