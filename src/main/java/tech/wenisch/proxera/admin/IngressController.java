package tech.wenisch.proxera.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.proxera.domain.IngressSpec;
import tech.wenisch.proxera.service.IngressService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/ingresses")
public class IngressController {

    private final IngressService ingressService;

    public IngressController(IngressService ingressService) {
        this.ingressService = ingressService;
    }

    @PostMapping
    public String createIngress(
            @RequestParam String name,
            @RequestParam(defaultValue = "") String className,
            @RequestParam(value = "annotationKeys", required = false) List<String> annotationKeys,
            @RequestParam(value = "annotationValues", required = false) List<String> annotationValues,
            @RequestParam(defaultValue = "") String host,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(defaultValue = "ImplementationSpecific") String pathType,
            @RequestParam(defaultValue = "false") boolean tlsEnabled,
            @RequestParam(required = false, defaultValue = "") String tlsSecretName,
            RedirectAttributes redirectAttributes) {

        if (!ingressService.isAvailable()) {
            redirectAttributes.addFlashAttribute("error", "Ingress management is only available when running in Kubernetes.");
            return "redirect:/admin/topology";
        }
        try {
            ingressService.createIngress(IngressSpec.builder()
                    .name(name.trim())
                    .className(className.trim())
                    .annotations(buildAnnotations(annotationKeys, annotationValues))
                    .host(host.trim())
                    .path(path.isBlank() ? "/" : path.trim())
                    .pathType(pathType)
                    .tlsEnabled(tlsEnabled)
                    .tlsSecretName(tlsSecretName.trim())
                    .build());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create ingress: " + e.getMessage());
        }
        return "redirect:/admin/topology";
    }

    @PostMapping("/{name}")
    public String updateIngress(
            @PathVariable String name,
            @RequestParam(defaultValue = "") String className,
            @RequestParam(value = "annotationKeys", required = false) List<String> annotationKeys,
            @RequestParam(value = "annotationValues", required = false) List<String> annotationValues,
            @RequestParam(defaultValue = "") String host,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(defaultValue = "ImplementationSpecific") String pathType,
            @RequestParam(defaultValue = "false") boolean tlsEnabled,
            @RequestParam(required = false, defaultValue = "") String tlsSecretName,
            RedirectAttributes redirectAttributes) {

        if (!ingressService.isAvailable()) {
            redirectAttributes.addFlashAttribute("error", "Ingress management is only available when running in Kubernetes.");
            return "redirect:/admin/topology";
        }
        try {
            ingressService.replaceIngress(name, IngressSpec.builder()
                    .name(name)
                    .className(className.trim())
                    .annotations(buildAnnotations(annotationKeys, annotationValues))
                    .host(host.trim())
                    .path(path.isBlank() ? "/" : path.trim())
                    .pathType(pathType)
                    .tlsEnabled(tlsEnabled)
                    .tlsSecretName(tlsSecretName.trim())
                    .build());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to update ingress: " + e.getMessage());
        }
        return "redirect:/admin/topology";
    }

    @PostMapping("/{name}/delete")
    public String deleteIngress(
            @PathVariable String name,
            RedirectAttributes redirectAttributes) {

        if (!ingressService.isAvailable()) {
            redirectAttributes.addFlashAttribute("error", "Ingress management is only available when running in Kubernetes.");
            return "redirect:/admin/topology";
        }
        try {
            ingressService.deleteIngress(name);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to delete ingress: " + e.getMessage());
        }
        return "redirect:/admin/topology";
    }

    private Map<String, String> buildAnnotations(List<String> keys, List<String> values) {
        Map<String, String> annotations = new LinkedHashMap<>();
        if (keys == null || values == null) return annotations;
        int size = Math.min(keys.size(), values.size());
        for (int i = 0; i < size; i++) {
            String k = keys.get(i).trim();
            if (!k.isEmpty()) {
                annotations.put(k, values.get(i).trim());
            }
        }
        return annotations;
    }
}
