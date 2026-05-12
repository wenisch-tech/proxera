package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("hostname", System.getenv().getOrDefault("HOSTNAME", "localhost"));
        return "admin/settings";
    }
}
