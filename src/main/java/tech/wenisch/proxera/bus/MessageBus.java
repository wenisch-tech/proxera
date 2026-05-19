package tech.wenisch.proxera.bus;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelFrame;

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

    /**
     * Route any TunnelFrame directly to a specific agent.
     * In InMemory mode: calls TunnelManager.sendFrame() directly.
     * In Redis mode: publishes the frame JSON to proxera:agent:{agentId} so that
     * the pod holding the agent's WebSocket session can forward it.
     *
     * Used for WS_OPEN routing and any future server-initiated frames.
     */
    void sendToAgent(UUID agentId, TunnelFrame frame) throws IOException;

    /**
     * Called by TunnelWebSocketHandler when an agent's tunnel WebSocket connects.
     * In Redis mode: sets up a dedicated subscription to proxera:agent:{agentId}
     * that forwards received TunnelFrames to the agent via TunnelManager.
     */
    default void onAgentConnected(UUID agentId) {}

    /**
     * Called by TunnelWebSocketHandler when an agent's tunnel WebSocket disconnects.
     * In Redis mode: tears down the proxera:agent:{agentId} subscription.
     */
    default void onAgentDisconnected(UUID agentId) {}
}
