package tech.wenisch.proxera.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import tech.wenisch.proxera.domain.Route;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.agent LEFT JOIN FETCH r.domains")
    List<Route> findAllWithAgent();

    @Query("SELECT r FROM Route r LEFT JOIN FETCH r.agent LEFT JOIN FETCH r.domains WHERE r.id = :id")
    Optional<Route> findByIdWithDetails(UUID id);

    List<Route> findByAgentId(UUID agentId);

    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.domains WHERE r.agent.id = :agentId")
    List<Route> findByAgentIdWithDomains(UUID agentId);
}
