package tech.wenisch.proxera.domain;

public enum AgentStatus {
    PENDING,
    REGISTERED,
    CONNECTED,
    DISCONNECTED;

    public String getBadgeClass() {
        return switch (this) {
            case CONNECTED -> "bg-success";
            case REGISTERED -> "bg-info";
            default -> "bg-secondary";
        };
    }
}
