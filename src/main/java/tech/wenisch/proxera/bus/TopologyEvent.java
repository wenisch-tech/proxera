package tech.wenisch.proxera.bus;

public record TopologyEvent(
        String type,       // CLIENT_CONNECTED, CLIENT_DISCONNECTED, ROUTE_UPDATED, REQUEST_IN_FLIGHT, REQUEST_COMPLETED
        String clientId,
        String name
) {}
