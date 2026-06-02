package tech.wenisch.proxera.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.repository.RouteDomainRepository;
import tech.wenisch.proxera.repository.RouteRepository;

@Service
@Slf4j
public class RouteService {

    @PersistenceContext
    private EntityManager entityManager;

    private final RouteRepository routeRepository;
    private final RouteDomainRepository routeDomainRepository;
    private final RoutingService routingService;

    public RouteService(RouteRepository routeRepository,
                        RouteDomainRepository routeDomainRepository,
                        RoutingService routingService) {
        this.routeRepository = routeRepository;
        this.routeDomainRepository = routeDomainRepository;
        this.routingService = routingService;
    }

    public List<Route> findAll() {
        return routeRepository.findAllWithAgent();
    }

    public Optional<Route> findById(UUID id) {
        return routeRepository.findByIdWithDetails(id);
    }

    public List<Route> findByAgentId(UUID agentId) {
        return routeRepository.findByAgentId(agentId);
    }

    public List<Route> findByAgentIdWithDomains(UUID agentId) {
        return routeRepository.findByAgentIdWithDomains(agentId);
    }

    @Transactional
    public Route save(Route route) {
        if (route.getId() != null) {
            // UPDATE: load a fresh managed entity in this transaction so we never
            // merge a detached object whose stale domain IDs confuse Hibernate.
            Route managed = routeRepository.findByIdWithDetails(route.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Route not found"));

            // Validate uniqueness against other routes
            for (RouteDomain entry : route.getDomains()) {
                if (routeDomainRepository.existsByDomainAndPathPrefixExcludingRoute(
                        entry.getDomain(), entry.getPathPrefix(), route.getId())) {
                    String path = entry.getPathPrefix() != null ? entry.getPathPrefix() : "/";
                    throw new IllegalArgumentException(
                            "Domain + path already in use: " + entry.getDomain() + path);
                }
            }

            // Copy scalar fields onto the managed entity
            managed.setName(route.getName());
            managed.setLocalHost(route.getLocalHost());
            managed.setLocalPort(route.getLocalPort());
            managed.setEnabled(route.isEnabled());
            managed.setForwardClientIpHeaders(route.isForwardClientIpHeaders());
            // Use getReference so the FK column is set without a SELECT for the agent
            managed.setAgent(entityManager.getReference(Agent.class, route.getAgent().getId()));

            // Phase 1: clear old domains → orphanRemoval marks them for DELETE
            managed.getDomains().clear();
            entityManager.flush(); // execute DELETEs before INSERTs

            // Phase 2: add new transient domains; will be INSERTed at transaction commit
            for (RouteDomain rd : route.getDomains()) {
                rd.setRoute(managed);
                managed.getDomains().add(rd);
            }

            routingService.invalidateCache();
            return managed;
        }

        // CREATE: validate and persist the new entity
        UUID tempId = UUID.randomUUID();
        for (RouteDomain entry : route.getDomains()) {
            if (routeDomainRepository.existsByDomainAndPathPrefixExcludingRoute(
                    entry.getDomain(), entry.getPathPrefix(), tempId)) {
                String path = entry.getPathPrefix() != null ? entry.getPathPrefix() : "/";
                throw new IllegalArgumentException(
                        "Domain + path already in use: " + entry.getDomain() + path);
            }
        }
        Route saved = routeRepository.save(route);
        routingService.invalidateCache();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        routeRepository.deleteById(id);
        routingService.invalidateCache();
    }

    public long countEnabled() {
        return routeRepository.findAll().stream().filter(Route::isEnabled).count();
    }
}
