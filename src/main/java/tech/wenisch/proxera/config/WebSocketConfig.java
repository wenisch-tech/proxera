package tech.wenisch.proxera.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tech.wenisch.proxera.tunnel.TunnelHandshakeInterceptor;
import tech.wenisch.proxera.tunnel.TunnelWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TunnelWebSocketHandler tunnelWebSocketHandler;
    private final TunnelHandshakeInterceptor tunnelHandshakeInterceptor;

    public WebSocketConfig(TunnelWebSocketHandler tunnelWebSocketHandler,
                           TunnelHandshakeInterceptor tunnelHandshakeInterceptor) {
        this.tunnelWebSocketHandler = tunnelWebSocketHandler;
        this.tunnelHandshakeInterceptor = tunnelHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tunnelWebSocketHandler, "/tunnel")
                .setAllowedOriginPatterns("*")
                .addInterceptors(tunnelHandshakeInterceptor);
    }
}
