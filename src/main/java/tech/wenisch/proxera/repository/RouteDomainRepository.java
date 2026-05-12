package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.proxera.domain.RouteDomain;

import java.util.Optional;
import java.util.UUID;

public interface RouteDomainRepository extends JpaRepository<RouteDomain, UUID> {
    Optional<RouteDomain> findByDomain(String domain);
    boolean existsByDomain(String domain);
    boolean existsByDomainAndRouteIdNot(String domain, UUID routeId);
}
