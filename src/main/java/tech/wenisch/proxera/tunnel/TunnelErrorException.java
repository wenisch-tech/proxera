package tech.wenisch.proxera.tunnel;

/**
 * Exception used internally when an agent returns an ERROR frame for a
 * correlated proxy request.
 */
public class TunnelErrorException extends RuntimeException {

    private final TunnelErrorPayload error;

    public TunnelErrorException(TunnelErrorPayload error) {
        super(buildMessage(error));
        this.error = error;
    }

    public TunnelErrorPayload error() {
        return error;
    }

    private static String buildMessage(TunnelErrorPayload error) {
        String code = error != null && error.code() != null ? error.code() : "unknown";
        String message = error != null && error.message() != null ? error.message() : "Tunnel error";
        return code + ": " + message;
    }
}
