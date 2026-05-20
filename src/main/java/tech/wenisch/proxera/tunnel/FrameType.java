package tech.wenisch.proxera.tunnel;

public enum FrameType {
    REGISTER_ACK,
    REQUEST,
    RESPONSE,
    PING,
    PONG,
    ERROR,
    WS_OPEN,
    WS_OPEN_ACK,
    WS_OPEN_REJECT,
    WS_DATA,
    WS_CLOSE
}
