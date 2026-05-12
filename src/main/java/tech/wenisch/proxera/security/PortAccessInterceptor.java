package tech.wenisch.proxera.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;

// @Component removed — port-based access control replaced by path-based security chains.
public class PortAccessInterceptor implements HandlerInterceptor {

    @Value("${proxera.admin.port:8081}")
    private int adminPort;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        int port = request.getLocalPort();
        String path = request.getRequestURI();

        // Admin paths are only accessible on the admin port
        if (isAdminPath(path) && port != adminPort) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        // Proxy / tunnel paths are only accessible on the proxy port
        if (!isAdminPath(path) && !isSharedPath(path) && port == adminPort) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        return true;
    }

    private boolean isAdminPath(String path) {
        return path.startsWith("/admin") || path.startsWith("/login") || path.startsWith("/logout");
    }

    private boolean isSharedPath(String path) {
        return path.startsWith("/webjars")
                || path.startsWith("/css")
                || path.startsWith("/js")
                || path.startsWith("/actuator")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api");
    }
}
