package tech.wenisch.proxera.tunnel;

import java.util.Map;

/**
 * Envelope for all WebSocket tunnel messages.
 */
public record TunnelFrame(
        String type,
        String correlationId,
        Map<String, Object> payload
) {
    public static TunnelFrame of(FrameType type, String correlationId, Map<String, Object> payload) {
        return new TunnelFrame(type.name(), correlationId, payload);
    }

    public static TunnelFrame ping(String correlationId) {
        return new TunnelFrame(FrameType.PING.name(), correlationId, Map.of());
    }

    public static TunnelFrame pong(String correlationId) {
        return new TunnelFrame(FrameType.PONG.name(), correlationId, Map.of());
    }
}
