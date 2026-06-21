package tech.wenisch.proxera.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import tech.wenisch.proxera.tunnel.ResponsePayload;

class RedisMessageBusTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisMessageListenerContainer listenerContainer =
            mock(RedisMessageListenerContainer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dispatchPublishesRequestAndCompletesFromCorrelationChannel() throws Exception {
        RedisMessageBus bus = new RedisMessageBus(redisTemplate, listenerContainer, objectMapper);
        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer).addMessageListener(listenerCaptor.capture(), any(PatternTopic.class));

        UUID agentId = UUID.randomUUID();
        CompletableFuture<ResponsePayload> future = bus.dispatch(agentId, "{\"path\":\"/manifest.json\"}", "corr-1");

        verify(redisTemplate).convertAndSend(eq(RedisMessageBus.CHANNEL_AGENT_PREFIX + agentId), any(String.class));
        assertThat(bus.pendingCount()).isEqualTo(1);

        ResponsePayload response = new ResponsePayload(200, Map.of(), null, 7);
        listenerCaptor.getValue().onMessage(message(
                RedisMessageBus.CHANNEL_CORR_PREFIX + "corr-1",
                objectMapper.writeValueAsString(response)), null);

        assertThat(future.get(1, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(bus.pendingCount()).isZero();
    }

    @Test
    void timedOutFutureRemovesPendingRequest() {
        RedisMessageBus bus = new RedisMessageBus(redisTemplate, listenerContainer, objectMapper);

        CompletableFuture<ResponsePayload> future = bus.dispatch(UUID.randomUUID(), "{\"path\":\"/\"}", "corr-2");
        future.completeExceptionally(new TimeoutException("timeout"));

        assertThat(bus.pendingCount()).isZero();
    }

    @Test
    void nonRequestingPodPublishesResponseToCorrelationChannel() throws Exception {
        RedisMessageBus bus = new RedisMessageBus(redisTemplate, listenerContainer, objectMapper);
        ResponsePayload response = new ResponsePayload(400, Map.of(), null, 3);

        bus.complete("corr-3", response);

        verify(redisTemplate).convertAndSend(eq(RedisMessageBus.CHANNEL_CORR_PREFIX + "corr-3"), any(String.class));
    }

    @Test
    void agentConnectAndDisconnectManageOneListener() {
        RedisMessageBus bus = new RedisMessageBus(redisTemplate, listenerContainer, objectMapper);
        reset(listenerContainer);

        UUID agentId = UUID.randomUUID();
        bus.onAgentConnected(agentId);

        assertThat(bus.agentListenerCount()).isEqualTo(1);
        verify(listenerContainer).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));

        bus.onAgentDisconnected(agentId);

        assertThat(bus.agentListenerCount()).isZero();
        verify(listenerContainer).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
    }

    private static Message message(String channel, String body) {
        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn(channel.getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
