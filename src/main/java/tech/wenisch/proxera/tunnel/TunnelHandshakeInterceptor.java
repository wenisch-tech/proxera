package tech.wenisch.proxera.tunnel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tech.wenisch.proxera.service.RegistrationTokenService;

import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class TunnelHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_HEADER = "X-Proxera-Token";

    private final RegistrationTokenService registrationTokenService;

    public TunnelHandshakeInterceptor(RegistrationTokenService registrationTokenService) {
        this.registrationTokenService = registrationTokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        String token = servletRequest.getServletRequest().getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            log.warn("WebSocket connect rejected — missing {} header", TOKEN_HEADER);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        return registrationTokenService.validate(token)
                .map(result -> {
                    attributes.put("clientId", result.clientId());
                    attributes.put("clientName", result.clientName());
                    return true;
                })
                .orElseGet(() -> {
                    log.warn("WebSocket connect rejected — invalid token");
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
