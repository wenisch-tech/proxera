package tech.wenisch.proxera.bus;

/**
 * Message payload relayed between Proxera pods for a proxied WebSocket session.
 *
 * Used internally by {@link WsRelayBus} implementations.  Never sent on the
 * tunnel wire — that transport uses {@link tech.wenisch.proxera.tunnel.TunnelFrame}.
 *
 * @param type   One of: OPEN_ACK, OPEN_REJECT, DATA, CLOSE
 * @param data   Base64-encoded WebSocket frame payload (DATA only)
 * @param binary True when the original WebSocket frame was a binary frame (DATA only)
 * @param code   WebSocket close code (CLOSE / OPEN_REJECT only)
 * @param reason WebSocket close reason (CLOSE / OPEN_REJECT only)
 */
public record WsRelayMessage(String type, String data, boolean binary, int code, String reason) {

    public static WsRelayMessage openAck() {
        return new WsRelayMessage("OPEN_ACK", null, false, 0, null);
    }

    public static WsRelayMessage openReject(int code, String reason) {
        return new WsRelayMessage("OPEN_REJECT", null, false, code, reason);
    }

    public static WsRelayMessage data(String base64Data, boolean binary) {
        return new WsRelayMessage("DATA", base64Data, binary, 0, null);
    }

    public static WsRelayMessage close(int code, String reason) {
        return new WsRelayMessage("CLOSE", null, false, code, reason);
    }
}
