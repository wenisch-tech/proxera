package tech.wenisch.proxera.admin;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("podId")
    public String podId() {
        return System.getenv().getOrDefault("HOSTNAME", "localhost");
    }
}
