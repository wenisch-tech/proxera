package tech.wenisch.proxera.admin;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import tech.wenisch.proxera.domain.AccessLog;
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

    @GetMapping("/recent")
    @ResponseBody
    public ResponseEntity<List<AccessLog>> recent() {
        return ResponseEntity.ok(accessLogService.getRecentGlobal(10));
    }
}
