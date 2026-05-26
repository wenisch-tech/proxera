package tech.wenisch.proxera.tunnel;

import java.util.List;
import java.util.Map;

/**
 * Payload carried inside a REQUEST tunnel frame.
 * The body is Base64-encoded (Phase 1 protocol).
 */
public record RequestPayload(
        String method,
        String path,
        String queryString,
        Map<String, List<String>> headers,
        String body,          // Base64-encoded, nullable
        String localHost,
        int localPort,
        String stripPrefix,   // path prefix to strip before forwarding, nullable
        String remoteAddress
) {}
