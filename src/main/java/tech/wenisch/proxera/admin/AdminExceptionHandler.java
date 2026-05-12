package tech.wenisch.proxera.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.ui.Model;

import java.io.PrintWriter;
import java.io.StringWriter;

@ControllerAdvice(basePackageClasses = AdminExceptionHandler.class)
public class AdminExceptionHandler {

    @ExceptionHandler(Exception.class)
    public String handleException(Exception ex, Model model, HttpServletRequest request) {
        model.addAttribute("errorMessage", ex.getMessage());
        model.addAttribute("stackTrace", toStackTrace(ex));
        return "admin/error";
    }

    private String toStackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
