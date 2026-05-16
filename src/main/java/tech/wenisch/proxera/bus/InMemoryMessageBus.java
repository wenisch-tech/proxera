package tech.wenisch.proxera.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelFrame;
import tech.wenisch.proxera.tunnel.TunnelManager;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory MessageBus for single-pod deployments.
 * Uses Spring ApplicationEventPublisher for topology events within the same JVM.
 * Request dispatch is handled directly via TunnelManager.
 */
@Slf4j
public class InMemoryMessageBus implements MessageBus {

    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, CompletableFuture<ResponsePayload>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<TopologyEvent>> topologySubscribers = new ConcurrentHashMap<>();

    private TunnelManager tunnelManager;
    private ObjectMapper objectMapper;

    public InMemoryMessageBus(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // Setter injection to avoid circular dependency
    public void setTunnelManager(TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CompletableFuture<ResponsePayload> dispatch(UUID agentId, String requestJson, String correlationId) {
        CompletableFuture<ResponsePayload> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        try {
            Map<String, Object> payload = objectMapper.readValue(requestJson, Map.class);
            TunnelFrame frame = TunnelFrame.of(FrameType.REQUEST, correlationId, payload);
            tunnelManager.sendFrame(agentId, frame);
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
            log.warn("No pending request for correlationId: {}", correlationId);
        }
    }

    @Override
    public void publishTopology(TopologyEvent event) {
        topologySubscribers.values().forEach(handler -> {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.warn("Topology subscriber error: {}", e.getMessage());
            }
        });
    }

    @Override
    public void subscribeTopology(Consumer<TopologyEvent> handler) {
        topologySubscribers.put(UUID.randomUUID().toString(), handler);
    }
}
