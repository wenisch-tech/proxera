package tech.wenisch.proxera.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelManager;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis-backed MessageBus for multi-pod deployments.
 * Uses Redis Pub/Sub to route request frames and response frames across pods.
 */
@Slf4j
public class RedisMessageBus implements MessageBus {

    private static final String CHANNEL_AGENT_PREFIX = "proxera:agent:";
    private static final String CHANNEL_CORR_PREFIX = "proxera:corr:";
    private static final String CHANNEL_TOPOLOGY = "proxera:topology";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<ResponsePayload>> pending = new ConcurrentHashMap<>();

    private TunnelManager tunnelManager;

    public RedisMessageBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public void setTunnelManager(TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    @Override
    public CompletableFuture<ResponsePayload> dispatch(UUID agentId, String requestJson, String correlationId) {
        CompletableFuture<ResponsePayload> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        // Subscribe to the response channel for this correlationId
        String responseChannel = CHANNEL_CORR_PREFIX + correlationId;
        redisTemplate.getConnectionFactory().getConnection()
                .subscribe((message, pattern) -> {
                    try {
                        ResponsePayload response = objectMapper.readValue(
                                new String(message.getBody()), ResponsePayload.class);
                        complete(correlationId, response);
                    } catch (IOException e) {
                        log.error("Failed to deserialize response for correlationId {}", correlationId, e);
                        future.completeExceptionally(e);
                    }
                }, responseChannel.getBytes());

        // Publish request to the agent's channel
        String requestChannel = CHANNEL_AGENT_PREFIX + agentId;
        try {
            String payload = objectMapper.writeValueAsString(
                    Map.of("correlationId", correlationId, "requestJson", requestJson));
            redisTemplate.convertAndSend(requestChannel, payload);
        } catch (JsonProcessingException e) {
            pending.remove(correlationId);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void complete(String correlationId, ResponsePayload response) {
        CompletableFuture<ResponsePayload> future = pending.remove(correlationId);
        if (future != null) {
            future.complete(response);
        } else {
            // This pod holds the WebSocket — publish the response so the requesting pod can pick it up
            try {
                String responseChannel = CHANNEL_CORR_PREFIX + correlationId;
                redisTemplate.convertAndSend(responseChannel, objectMapper.writeValueAsString(response));
            } catch (JsonProcessingException e) {
                log.error("Failed to publish response for correlationId {}", correlationId, e);
            }
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
        redisTemplate.getConnectionFactory().getConnection()
                .subscribe((message, pattern) -> {
                    try {
                        TopologyEvent event = objectMapper.readValue(
                                new String(message.getBody()), TopologyEvent.class);
                        handler.accept(event);
                    } catch (IOException e) {
                        log.warn("Failed to deserialize topology event: {}", e.getMessage());
                    }
                }, CHANNEL_TOPOLOGY.getBytes());
    }
}
