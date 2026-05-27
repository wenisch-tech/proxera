package tech.wenisch.proxera.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import tech.wenisch.proxera.service.RoutingService;

/**
 * RequestMatcher that returns {@code true} when a request should be handled
 * by the admin security chain (login, admin UI, API, etc.).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Explicit admin host</b> ({@code proxera.admin.host} is set): only
 *       requests whose {@code Host} header matches that value enter the admin
 *       chain. All other hosts are proxied.</li>
 *   <li><b>Dynamic mode</b> (property is empty, the default): requests whose
 *       host matches a configured proxy route domain are excluded from the
 *       admin chain and are always proxied. All other hosts (including the raw
 *       server IP/hostname) continue to use the admin chain as before.</li>
 * </ul>
 */
@Component
public class ProxyHostRequestMatcher implements RequestMatcher {

    private final RoutingService routingService;
    private final String adminHost;

    public ProxyHostRequestMatcher(
            RoutingService routingService,
            @Value("${proxera.admin.host:}") String adminHost) {
        this.routingService = routingService;
        this.adminHost = adminHost == null ? "" : adminHost.trim();
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) {
            // No Host header — let admin chain handle it (safe fallback)
            return true;
        }
        // Strip port for comparison
        String cleanHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        if (!adminHost.isEmpty()) {
            // Explicit mode: admin chain only for the configured admin host
            return adminHost.equalsIgnoreCase(cleanHost);
        }

        // Dynamic mode: admin chain only when host is NOT a proxy route domain
        return !routingService.isKnownProxyDomain(host);
    }
}
