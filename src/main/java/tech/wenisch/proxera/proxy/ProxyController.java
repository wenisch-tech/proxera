package tech.wenisch.proxera.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import java.io.IOException;

/**
 * Combined HandlerMapping + HttpRequestHandler for reverse-proxy traffic.
 *
 * Registered at LOWEST_PRECEDENCE so DispatcherServlet only reaches here after
 * all other HandlerMappings (ResourceHandlerMapping at -1, RequestMappingHandlerMapping
 * at 0) have returned null.  This means /login, /admin/**, /actuator/**, etc. are
 * always handled by their own controllers; only truly unmatched requests (inbound
 * proxy traffic) reach this handler.
 */
@Component
public class ProxyController extends AbstractHandlerMapping implements HttpRequestHandler {

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
        setOrder(Ordered.LOWEST_PRECEDENCE);
    }

    /** Always return ourselves — we are the last-resort catch-all. */
    @Override
    protected Object getHandlerInternal(@NonNull HttpServletRequest request) {
        return this;
    }

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AsyncContext asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(31_000);
        proxyService.proxy(request, response, asyncContext);
    }
}
