package tech.wenisch.proxera.bus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-process {@link WsRelayBus} for single-pod deployments.
 *
 * All publish calls are dispatched synchronously to the registered handler in the
 * calling thread.  No external dependencies required.
 *
 * Activated by {@link tech.wenisch.proxera.config.MessageBusConfig} when REDIS_HOST
 * is absent.
 */
public class InMemoryWsRelayBus implements WsRelayBus {

    private final ConcurrentHashMap<String, Consumer<WsRelayMessage>> a2cHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<WsRelayMessage>> c2aHandlers = new ConcurrentHashMap<>();

    @Override
    public void subscribeA2C(String wsSessionId, Consumer<WsRelayMessage> handler) {
        a2cHandlers.put(wsSessionId, handler);
    }

    @Override
    public void subscribeC2A(String wsSessionId, Consumer<WsRelayMessage> handler) {
        c2aHandlers.put(wsSessionId, handler);
    }

    @Override
    public void publishA2C(String wsSessionId, WsRelayMessage message) {
        Consumer<WsRelayMessage> handler = a2cHandlers.get(wsSessionId);
        if (handler != null) {
            handler.accept(message);
        }
    }

    @Override
    public void publishC2A(String wsSessionId, WsRelayMessage message) {
        Consumer<WsRelayMessage> handler = c2aHandlers.get(wsSessionId);
        if (handler != null) {
            handler.accept(message);
        }
    }

    @Override
    public void unsubscribe(String wsSessionId) {
        a2cHandlers.remove(wsSessionId);
        c2aHandlers.remove(wsSessionId);
    }
}
