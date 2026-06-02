package tech.wenisch.proxera.proxy;

import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import tech.wenisch.proxera.service.RoutingService;

/**
 * High-priority HandlerMapping that claims every request whose {@code Host}
 * header resolves to a configured proxy route domain.
 *
 * <p>By running at {@link Ordered#HIGHEST_PRECEDENCE}, this mapping wins before
 * {@code RequestMappingHandlerMapping} (Order 0), which would otherwise dispatch
 * paths like {@code /login} or {@code /api/**} to Proxera's own admin controllers
 * even when the request is destined for a proxied backend.
 *
 * <p>When a proxy-domain request is detected, {@link ProxyController} is returned
 * as the handler. Spring MVC invokes it via {@code HttpRequestHandlerAdapter},
 * which in turn delegates to {@link ProxyService} (or {@link WebSocketProxyService}
 * for WebSocket upgrades) — exactly as if the request had fallen through to
 * {@code ProxyController}'s own catch-all handler mapping.
 *
 * <p>For requests whose host is <em>not</em> a proxy domain (e.g. the Proxera
 * admin host), {@code null} is returned so the normal handler resolution chain
 * continues unaffected.
 */
@Component
public class ProxyDomainHandlerMapping extends AbstractHandlerMapping {

    private final RoutingService routingService;
    private final ProxyController proxyController;

    public ProxyDomainHandlerMapping(RoutingService routingService, ProxyController proxyController) {
        this.routingService = routingService;
        this.proxyController = proxyController;
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected Object getHandlerInternal(@NonNull HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return null;
        }
        String host = request.getHeader("Host");
        if (routingService.isKnownProxyDomain(host)) {
            return proxyController;
        }
        return null;
    }
}
