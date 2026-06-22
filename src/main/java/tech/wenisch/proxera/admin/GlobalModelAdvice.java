package tech.wenisch.proxera.admin;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import tech.wenisch.proxera.config.PodIdentityResolver;

@ControllerAdvice
public class GlobalModelAdvice {

    private final PodIdentityResolver podIdentityResolver;

    public GlobalModelAdvice(PodIdentityResolver podIdentityResolver) {
        this.podIdentityResolver = podIdentityResolver;
    }

    @ModelAttribute("podId")
    public String podId() {
        return podIdentityResolver.currentPodId().orElse("localhost");
    }
}
