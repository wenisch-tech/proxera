package tech.wenisch.proxera.bus;

public record TopologyEvent(
        String type,       // AGENT_CONNECTED, AGENT_DISCONNECTED, ROUTE_UPDATED, REQUEST_IN_FLIGHT, REQUEST_COMPLETED
        String agentId,
        String name
) {}
