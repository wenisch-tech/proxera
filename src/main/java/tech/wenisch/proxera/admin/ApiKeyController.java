package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.service.ApiKeyService;

import java.util.UUID;

@Controller
@RequestMapping("/admin/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public String list(Model model) {
        var apiKeys = apiKeyService.findAll();
        model.addAttribute("apiKeys", apiKeys);
        model.addAttribute("hasApiKeys", !apiKeys.isEmpty());
        return "admin/api-keys";
    }

    @PostMapping
    public String generate(@RequestParam String name, RedirectAttributes ra) {
        String raw = apiKeyService.generate(name);
        ra.addFlashAttribute("newKey", raw);
        ra.addFlashAttribute("success", "API key generated. Copy it now — it will not be shown again.");
        return "redirect:/admin/api-keys";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable UUID id, RedirectAttributes ra) {
        apiKeyService.revoke(id);
        ra.addFlashAttribute("success", "API key revoked.");
        return "redirect:/admin/api-keys";
    }
}
