package tech.wenisch.proxera.tunnel;

import java.util.List;
import java.util.Map;

/**
 * Payload carried inside a RESPONSE tunnel frame.
 * The body is Base64-encoded (Phase 1 protocol).
 */
public record ResponsePayload(
        int status,
        Map<String, List<String>> headers,
        String body,       // Base64-encoded, nullable
        long latencyMs
) {}
