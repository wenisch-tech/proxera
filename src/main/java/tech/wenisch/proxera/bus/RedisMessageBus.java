package tech.wenisch.proxera.bus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelFrame;
import tech.wenisch.proxera.tunnel.TunnelManager;

/**
 * Redis-backed MessageBus for multi-pod deployments.
 * Uses non-blocking Spring Redis listener containers for all Pub/Sub subscriptions.
 */
@Slf4j
public class RedisMessageBus implements MessageBus {

    static final String CHANNEL_AGENT_PREFIX = "proxera:agent:";
    static final String CHANNEL_CORR_PREFIX = "proxera:corr:";
    static final String CHANNEL_TOPOLOGY = "proxera:topology";
    static final String CHANNEL_CORR_PATTERN = CHANNEL_CORR_PREFIX + "*";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<ResponsePayload>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MessageListener> agentListeners = new ConcurrentHashMap<>();

    private TunnelManager tunnelManager;

    public RedisMessageBus(StringRedisTemplate redisTemplate,
                           RedisMessageListenerContainer listenerContainer,
                           ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.listenerContainer.addMessageListener(this::handleCorrelationMessage,
                new PatternTopic(CHANNEL_CORR_PATTERN));
    }

    public void setTunnelManager(TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    @Override
    public CompletableFuture<ResponsePayload> dispatch(UUID agentId, String requestJson, String correlationId) {
        CompletableFuture<ResponsePayload> future = new CompletableFuture<>();
        pending.put(correlationId, future);
        future.whenComplete((response, ex) -> pending.remove(correlationId));

        try {
            Map<String, Object> payload = objectMapper.readValue(requestJson, Map.class);
            TunnelFrame frame = TunnelFrame.of(FrameType.REQUEST, correlationId, payload);
            sendToAgent(agentId, frame);
        } catch (IOException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void complete(String correlationId, ResponsePayload response) {
        if (!completeLocal(correlationId, response)) {
            publishResponse(correlationId, response);
        }
    }

    @Override
    public void sendToAgent(UUID agentId, TunnelFrame frame) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(frame);
            redisTemplate.convertAndSend(CHANNEL_AGENT_PREFIX + agentId, json);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize TunnelFrame for agent " + agentId, e);
        }
    }

    @Override
    public void onAgentConnected(UUID agentId) {
        onAgentDisconnected(agentId);

        String channel = CHANNEL_AGENT_PREFIX + agentId;
        MessageListener listener = (message, pattern) -> {
            try {
                TunnelFrame frame = objectMapper.readValue(body(message), TunnelFrame.class);
                tunnelManager.sendFrame(agentId, frame);
            } catch (Exception e) {
                log.error("Failed to relay frame to agent {}: {}", agentId, e.getMessage());
            }
        };

        agentListeners.put(agentId, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        log.debug("Redis agent channel listener registered for agent {}", agentId);
    }

    @Override
    public void onAgentDisconnected(UUID agentId) {
        MessageListener listener = agentListeners.remove(agentId);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener, new ChannelTopic(CHANNEL_AGENT_PREFIX + agentId));
        }
    }

    @Override
    public void publishTopology(TopologyEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL_TOPOLOGY, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.error("Failed to publish topology event", e);
        }
    }

    @Override
    public void subscribeTopology(Consumer<TopologyEvent> handler) {
        MessageListener listener = (message, pattern) -> {
            try {
                TopologyEvent event = objectMapper.readValue(body(message), TopologyEvent.class);
                handler.accept(event);
            } catch (IOException e) {
                log.warn("Failed to deserialize topology event: {}", e.getMessage());
            }
        };
        listenerContainer.addMessageListener(listener, new ChannelTopic(CHANNEL_TOPOLOGY));
    }

    int pendingCount() {
        return pending.size();
    }

    int agentListenerCount() {
        return agentListeners.size();
    }

    private void handleCorrelationMessage(Message message, byte[] pattern) {
        String channel = channel(message);
        if (!channel.startsWith(CHANNEL_CORR_PREFIX)) {
            return;
        }

        String correlationId = channel.substring(CHANNEL_CORR_PREFIX.length());
        try {
            ResponsePayload response = objectMapper.readValue(body(message), ResponsePayload.class);
            completeLocal(correlationId, response);
        } catch (IOException e) {
            CompletableFuture<ResponsePayload> future = pending.remove(correlationId);
            if (future != null) {
                future.completeExceptionally(e);
            }
            log.error("Failed to deserialize response for correlationId {}", correlationId, e);
        }
    }

    private boolean completeLocal(String correlationId, ResponsePayload response) {
        CompletableFuture<ResponsePayload> future = pending.remove(correlationId);
        if (future != null) {
            future.complete(response);
            return true;
        }
        log.debug("No local pending request for correlationId {}", correlationId);
        return false;
    }

    private void publishResponse(String correlationId, ResponsePayload response) {
        try {
            redisTemplate.convertAndSend(CHANNEL_CORR_PREFIX + correlationId,
                    objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            log.error("Failed to publish response for correlationId {}", correlationId, e);
        }
    }

    private static String channel(Message message) {
        return new String(message.getChannel(), StandardCharsets.UTF_8);
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
