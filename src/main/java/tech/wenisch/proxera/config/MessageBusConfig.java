package tech.wenisch.proxera.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import tech.wenisch.proxera.bus.InMemoryMessageBus;
import tech.wenisch.proxera.bus.InMemoryWsRelayBus;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.RedisMessageBus;
import tech.wenisch.proxera.bus.RedisWsRelayBus;
import tech.wenisch.proxera.bus.WsRelayBus;
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

    /**
     * In-memory WsRelayBus for single-pod deployments.
     * Handlers are dispatched in-process; no external dependency.
     */
    @Bean
    @ConditionalOnExpression("'${REDIS_HOST:}'.isEmpty()")
    public WsRelayBus inMemoryWsRelayBus() {
        return new InMemoryWsRelayBus();
    }

    /**
     * Redis-backed WsRelayBus for multi-pod deployments.
     * Uses a single pSubscribe on proxera:ws:* to demux all active WS sessions.
     */
    @Bean
    @ConditionalOnExpression("!'${REDIS_HOST:}'.isEmpty()")
    public WsRelayBus redisWsRelayBus(StringRedisTemplate redisTemplate) {
        return new RedisWsRelayBus(redisTemplate);
    }
}
