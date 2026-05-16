package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.domain.Settings;
import tech.wenisch.proxera.service.SettingsService;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("hostname", System.getenv().getOrDefault("HOSTNAME", "localhost"));
        model.addAttribute("settings", settingsService.get());
        return "admin/settings";
    }

    @PostMapping
    public String save(@ModelAttribute("settings") Settings settings, RedirectAttributes redirectAttributes) {
        settingsService.save(settings);
        redirectAttributes.addFlashAttribute("success", "Settings saved.");
        return "redirect:/admin/settings";
    }
}
