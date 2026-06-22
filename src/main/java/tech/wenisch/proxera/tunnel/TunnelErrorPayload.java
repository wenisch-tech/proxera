package tech.wenisch.proxera.tunnel;

/**
 * Payload carried inside an ERROR tunnel frame.
 */
public record TunnelErrorPayload(
        String code,
        String message
) {}
