package tech.wenisch.proxera.bus;

import java.util.function.Consumer;

/**
 * Bidirectional pub/sub relay for active proxied WebSocket sessions.
 *
 * Two logical channels exist per session:
 * <ul>
 *   <li><b>a2c</b> (agent-to-client): frames from the agent's LAN service toward the client</li>
 *   <li><b>c2a</b> (client-to-agent): frames from the client toward the agent's LAN service</li>
 * </ul>
 *
 * Two implementations are provided:
 * <ul>
 *   <li>{@link InMemoryWsRelayBus} — single-pod; handlers are called directly in-process</li>
 *   <li>{@link RedisWsRelayBus} — multi-pod; uses Redis Pub/Sub channels
 *       {@code proxera:ws:{wsSessionId}:a2c} and {@code proxera:ws:{wsSessionId}:c2a}</li>
 * </ul>
 */
public interface WsRelayBus {

    /** Register a handler for agent-to-client messages on this session. */
    void subscribeA2C(String wsSessionId, Consumer<WsRelayMessage> handler);

    /** Register a handler for client-to-agent messages on this session. */
    void subscribeC2A(String wsSessionId, Consumer<WsRelayMessage> handler);

    /** Publish a message from the agent toward the client. */
    void publishA2C(String wsSessionId, WsRelayMessage message);

    /** Publish a message from the client toward the agent. */
    void publishC2A(String wsSessionId, WsRelayMessage message);

    /** Remove all handlers for this session (both directions). */
    void unsubscribe(String wsSessionId);
}
