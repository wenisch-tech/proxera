package tech.wenisch.proxera.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
     * Runtime-selected message bus.
     *
     * Native-image AOT can evaluate conditional annotations during build. This
     * bean stays unconditional so REDIS_HOST is honored in the deployed pod.
     */
    @Bean
    @Primary
    public MessageBus messageBus(ApplicationEventPublisher eventPublisher,
                                 TunnelManager tunnelManager,
                                 ObjectMapper objectMapper,
                                 RedisRuntimeService redisRuntimeService) {
        if (redisRuntimeService.isAvailable()) {
            RedisMessageBus bus = new RedisMessageBus(redisRuntimeService.redisTemplate(),
                    redisRuntimeService.listenerContainer(), objectMapper);
            bus.setTunnelManager(tunnelManager);
            return bus;
        }

        InMemoryMessageBus bus = new InMemoryMessageBus(eventPublisher);
        bus.setTunnelManager(tunnelManager);
        bus.setObjectMapper(objectMapper);
        return bus;
    }

    @Bean
    public WsRelayBus wsRelayBus(RedisRuntimeService redisRuntimeService) {
        if (redisRuntimeService.isAvailable()) {
            return new RedisWsRelayBus(redisRuntimeService.redisTemplate(),
                    redisRuntimeService.listenerContainer());
        }
        return new InMemoryWsRelayBus();
    }
}
