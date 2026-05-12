package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tech.wenisch.proxera.domain.Route;

import java.util.List;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.client LEFT JOIN FETCH r.domains")
    List<Route> findAllWithClient();

    List<Route> findByClientId(UUID clientId);

    @Query("SELECT r FROM Route r JOIN r.domains d WHERE d.domain = :domain AND r.enabled = true")
    List<Route> findEnabledByDomain(String domain);
}
