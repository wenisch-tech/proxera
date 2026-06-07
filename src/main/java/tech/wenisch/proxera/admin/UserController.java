package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.service.UserService;

import java.util.UUID;

@Controller
@RequestMapping("/admin/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model) {
        var users = userService.findAll();
        model.addAttribute("users", users);
        model.addAttribute("hasUsers", !users.isEmpty());
        return "admin/users";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam(defaultValue = "ROLE_ADMIN") String role,
                         RedirectAttributes ra) {
        try {
            userService.create(username, password, role);
            ra.addFlashAttribute("success", "User created.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        userService.delete(id);
        ra.addFlashAttribute("success", "User deleted.");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/password")
    public String changePassword(@PathVariable UUID id,
                                 @RequestParam String newPassword,
                                 RedirectAttributes ra) {
        userService.changePassword(id, newPassword);
        ra.addFlashAttribute("success", "Password changed.");
        return "redirect:/admin/users";
    }
}
