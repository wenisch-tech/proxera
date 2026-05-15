package tech.wenisch.proxera.admin;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ProxeraErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object message    = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);

        String errorMessage;
        if (statusCode != null) {
            int code = Integer.parseInt(statusCode.toString());
            HttpStatus status = HttpStatus.resolve(code);
            String reason = status != null ? status.getReasonPhrase() : "Unknown Error";
            errorMessage = code + " – " + reason;
            if (message != null && !message.toString().isBlank()) {
                errorMessage += ": " + message;
            }
        } else {
            errorMessage = message != null ? message.toString() : "An unexpected error occurred.";
        }

        model.addAttribute("errorMessage", errorMessage);
        return "admin/error";
    }
}
