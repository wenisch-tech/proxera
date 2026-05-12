package tech.wenisch.proxera.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "access_log",
        indexes = {
                @Index(name = "idx_access_log_route_ts", columnList = "route_id, timestamp DESC"),
                @Index(name = "idx_access_log_ts", columnList = "timestamp DESC")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "remote_ip", length = 45)
    private String remoteIp;
}
