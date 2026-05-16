package tech.wenisch.proxera.bus;

import tech.wenisch.proxera.tunnel.ResponsePayload;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Abstracted message bus for dispatching request frames to tunnel agents
 * and routing response frames back.
 *
 * Two implementations:
 * - {@link InMemoryMessageBus}  — default, single-pod, no external dependency
 * - {@link RedisMessageBus}     — multi-pod, requires Redis
 */
public interface MessageBus {

    /**
     * Dispatch a serialised request payload to the given agent.
     * Returns a future that will be completed when the agent sends its RESPONSE frame.
     *
     * @param agentId       target agent
     * @param requestJson   JSON-serialised RequestPayload
     * @param correlationId UUID used to correlate the response
     */
    CompletableFuture<ResponsePayload> dispatch(UUID agentId, String requestJson, String correlationId);

    /**
     * Called by the WebSocket handler when a RESPONSE frame is received.
     * Completes the pending future for this correlationId.
     */
    void complete(String correlationId, ResponsePayload response);

    /**
     * Publish a topology change event (agent connected/disconnected, route updated).
     * In Redis mode, broadcasts to all pods.
     */
    void publishTopology(TopologyEvent event);

    /**
     * Subscribe to topology events from any pod.
     */
    void subscribeTopology(Consumer<TopologyEvent> handler);
}
