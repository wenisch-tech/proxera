package tech.wenisch.proxera.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "route_domains")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(nullable = false)
    private String domain;

    @Column(name = "path_prefix")
    private String pathPrefix;

    @Column(name = "strip_prefix", nullable = false)
    private boolean stripPrefix;

    @Transient
    public String getBadgeLabel() {
        return domain + (pathPrefix == null ? "" : pathPrefix);
    }

    @Transient
    public String getPathPrefixDisplay() {
        return pathPrefix == null || pathPrefix.isBlank() ? "/" : pathPrefix;
    }

    @Transient
    public String getStripPrefixDisplay() {
        return stripPrefix ? "Yes" : "No";
    }
}
