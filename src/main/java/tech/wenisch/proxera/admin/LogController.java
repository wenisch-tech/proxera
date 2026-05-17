package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tech.wenisch.proxera.service.AccessLogService;

@Controller
@RequestMapping("/admin/logs")
public class LogController {

    private final AccessLogService accessLogService;

    public LogController(AccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("logs", accessLogService.getRecentGlobal(200));
        return "admin/logs";
    }
}
