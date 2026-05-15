package tech.wenisch.proxera.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import tech.wenisch.proxera.tunnel.TunnelHandshakeInterceptor;
import tech.wenisch.proxera.tunnel.TunnelWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final int WS_MAX_MESSAGE_SIZE = 64 * 1024 * 1024; // 64 MB

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

    /**
     * Configures Tomcat's underlying WebSocket container buffer sizes.
     * Without this, Tomcat rejects frames larger than its default 8192-byte buffer
     * before Spring's per-session limit even gets a chance to apply.
     * Skips gracefully in test contexts where no real Tomcat ServerContainer is present.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean() {
            @Override
            public void afterPropertiesSet() {
                try {
                    super.afterPropertiesSet();
                } catch (IllegalStateException e) {
                    // No real Tomcat ServerContainer (e.g. MockServletContext in tests) — skip
                }
            }
        };
        container.setMaxTextMessageBufferSize(WS_MAX_MESSAGE_SIZE);
        container.setMaxBinaryMessageBufferSize(WS_MAX_MESSAGE_SIZE);
        return container;
    }
}
