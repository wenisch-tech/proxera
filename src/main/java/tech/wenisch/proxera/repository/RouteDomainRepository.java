package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.proxera.domain.RouteDomain;

import java.util.UUID;

public interface RouteDomainRepository extends JpaRepository<RouteDomain, UUID> {

    @Modifying
    @Query("DELETE FROM RouteDomain rd WHERE rd.route.id = :routeId")
    void deleteAllByRouteId(@Param("routeId") UUID routeId);

    /**
     * Returns true if a RouteDomain with the given domain + pathPrefix already exists
     * for a route other than the one identified by excludeRouteId.
     * Handles NULL pathPrefix correctly (NULL == NULL treated as duplicate).
     */
    @Query("SELECT CASE WHEN COUNT(rd) > 0 THEN true ELSE false END FROM RouteDomain rd " +
           "WHERE rd.domain = :domain " +
           "AND ((:pathPrefix IS NULL AND rd.pathPrefix IS NULL) OR rd.pathPrefix = :pathPrefix) " +
           "AND rd.route.id != :excludeRouteId")
    boolean existsByDomainAndPathPrefixExcludingRoute(
            @Param("domain") String domain,
            @Param("pathPrefix") String pathPrefix,
            @Param("excludeRouteId") UUID excludeRouteId);
}
