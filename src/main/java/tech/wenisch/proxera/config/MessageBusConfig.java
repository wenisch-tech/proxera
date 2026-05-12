package tech.wenisch.proxera.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import tech.wenisch.proxera.bus.InMemoryMessageBus;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.RedisMessageBus;
import tech.wenisch.proxera.tunnel.TunnelManager;

@Configuration
public class MessageBusConfig {

    /**
     * In-memory message bus (default for single-pod deployments).
     * Used when REDIS_HOST environment variable is absent or empty.
     */
    @Bean
    @Primary
    @ConditionalOnExpression("'${REDIS_HOST:}'.isEmpty()")
    public MessageBus inMemoryMessageBus(ApplicationEventPublisher eventPublisher,
                                         TunnelManager tunnelManager,
                                         ObjectMapper objectMapper) {
        InMemoryMessageBus bus = new InMemoryMessageBus(eventPublisher);
        bus.setTunnelManager(tunnelManager);
        bus.setObjectMapper(objectMapper);
        return bus;
    }

    /**
     * Redis-backed message bus for multi-pod deployments.
     * Activated when REDIS_HOST environment variable is set to a non-empty value.
     */
    @Bean
    @ConditionalOnExpression("!'${REDIS_HOST:}'.isEmpty()")
    public MessageBus redisMessageBus(StringRedisTemplate redisTemplate,
                                       TunnelManager tunnelManager) {
        RedisMessageBus bus = new RedisMessageBus(redisTemplate);
        bus.setTunnelManager(tunnelManager);
        return bus;
    }
}
