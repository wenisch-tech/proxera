package tech.wenisch.proxera.bus;

import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelErrorPayload;

record CorrelationResultMessage(
        String type,
        ResponsePayload response,
        TunnelErrorPayload error
) {
    static final String TYPE_RESPONSE = "RESPONSE";
    static final String TYPE_ERROR = "ERROR";

    static CorrelationResultMessage response(ResponsePayload response) {
        return new CorrelationResultMessage(TYPE_RESPONSE, response, null);
    }

    static CorrelationResultMessage error(TunnelErrorPayload error) {
        return new CorrelationResultMessage(TYPE_ERROR, null, error);
    }
}
