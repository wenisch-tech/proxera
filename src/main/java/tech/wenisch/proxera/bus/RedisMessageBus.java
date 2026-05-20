package tech.wenisch.proxera.bus;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelFrame;
import tech.wenisch.proxera.tunnel.TunnelManager;

/**
 * Redis-backed MessageBus for multi-pod deployments.
 * Uses Redis Pub/Sub to route request frames and response frames across pods.
 *
 * Fix: onAgentConnected() sets up a dedicated per-agent Redis subscription so that
 * TunnelFrames published by any pod (via sendToAgent) reach the pod that actually
 * holds the agent's WebSocket session.
 */
@Slf4j
public class RedisMessageBus implements MessageBus {

    private static final String CHANNEL_AGENT_PREFIX = "proxera:agent:";
    private static final String CHANNEL_CORR_PREFIX = "proxera:corr:";
    private static final String CHANNEL_TOPOLOGY = "proxera:topology";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<ResponsePayload>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RedisConnection> agentConnections = new ConcurrentHashMap<>();

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

        // Build a REQUEST TunnelFrame and route it to the agent's pod via the agent channel
        try {
            Map<String, Object> payload = objectMapper.readValue(requestJson, Map.class);
            TunnelFrame frame = TunnelFrame.of(FrameType.REQUEST, correlationId, payload);
            sendToAgent(agentId, frame);
        } catch (IOException e) {
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
    public void sendToAgent(UUID agentId, TunnelFrame frame) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(frame);
            redisTemplate.convertAndSend(CHANNEL_AGENT_PREFIX + agentId, json);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize TunnelFrame for agent " + agentId, e);
        }
    }

    /**
     * Called when an agent's tunnel WebSocket connects to THIS pod.
     * Sets up a dedicated Redis subscription to proxera:agent:{agentId} so that
     * TunnelFrames published by any pod are forwarded to the agent via TunnelManager.
     */
    @Override
    public void onAgentConnected(UUID agentId) {
        String channel = CHANNEL_AGENT_PREFIX + agentId;
        RedisConnection conn = redisTemplate.getConnectionFactory().getConnection();
        agentConnections.put(agentId, conn);
        conn.subscribe((message, pattern) -> {
            try {
                TunnelFrame frame = objectMapper.readValue(new String(message.getBody()), TunnelFrame.class);
                tunnelManager.sendFrame(agentId, frame);
            } catch (Exception e) {
                log.error("Failed to relay frame to agent {}: {}", agentId, e.getMessage());
            }
        }, channel.getBytes());
        log.debug("Redis agent channel subscription established for agent {}", agentId);
    }

    /**
     * Called when an agent's tunnel WebSocket disconnects from THIS pod.
     * Closes the dedicated agent channel subscription.
     */
    @Override
    public void onAgentDisconnected(UUID agentId) {
        RedisConnection conn = agentConnections.remove(agentId);
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("Error closing Redis agent subscription for agent {}: {}", agentId, e.getMessage());
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
