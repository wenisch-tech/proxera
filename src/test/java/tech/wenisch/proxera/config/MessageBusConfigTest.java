package tech.wenisch.proxera.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import tools.jackson.databind.ObjectMapper;

import tech.wenisch.proxera.bus.InMemoryMessageBus;
import tech.wenisch.proxera.bus.InMemoryWsRelayBus;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.bus.RedisMessageBus;
import tech.wenisch.proxera.bus.RedisWsRelayBus;
import tech.wenisch.proxera.bus.WsRelayBus;
import tech.wenisch.proxera.tunnel.TunnelManager;

class MessageBusConfigTest {

    private final MessageBusConfig config = new MessageBusConfig();
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TunnelManager tunnelManager = mock(TunnelManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selectsInMemoryBusesWhenRedisIsNotConfiguredAtRuntime() {
        RedisRuntimeService redisRuntimeService = mock(RedisRuntimeService.class);
        when(redisRuntimeService.isAvailable()).thenReturn(false);

        MessageBus messageBus = config.messageBus(eventPublisher, tunnelManager, objectMapper, redisRuntimeService);
        WsRelayBus wsRelayBus = config.wsRelayBus(redisRuntimeService);

        assertThat(messageBus).isInstanceOf(InMemoryMessageBus.class);
        assertThat(wsRelayBus).isInstanceOf(InMemoryWsRelayBus.class);
    }

    @Test
    void selectsRedisBusesWhenRedisIsConfiguredAtRuntime() {
        RedisRuntimeService redisRuntimeService = mock(RedisRuntimeService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer listenerContainer = mock(RedisMessageListenerContainer.class);
        when(redisRuntimeService.isAvailable()).thenReturn(true);
        when(redisRuntimeService.redisTemplate()).thenReturn(redisTemplate);
        when(redisRuntimeService.listenerContainer()).thenReturn(listenerContainer);

        MessageBus messageBus = config.messageBus(eventPublisher, tunnelManager, objectMapper, redisRuntimeService);
        WsRelayBus wsRelayBus = config.wsRelayBus(redisRuntimeService);

        assertThat(messageBus).isInstanceOf(RedisMessageBus.class);
        assertThat(wsRelayBus).isInstanceOf(RedisWsRelayBus.class);
    }
}
