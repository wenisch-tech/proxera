package tech.wenisch.proxera.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "route_domains",
        uniqueConstraints = @UniqueConstraint(columnNames = "domain"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(nullable = false)
    private String domain;
}
