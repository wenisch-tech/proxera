package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.repository.RouteDomainRepository;
import tech.wenisch.proxera.repository.RouteRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class RouteService {

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
        return routeRepository.findAll();
    }

    public Optional<Route> findById(UUID id) {
        return routeRepository.findById(id);
    }

    public List<Route> findByClientId(UUID clientId) {
        return routeRepository.findByClientId(clientId);
    }

    @Transactional
    public Route save(Route route) {
        // Validate domain uniqueness
        for (RouteDomain domain : route.getDomains()) {
            if (routeDomainRepository.existsByDomainAndRouteIdNot(domain.getDomain(),
                    route.getId() != null ? route.getId() : UUID.randomUUID())) {
                throw new IllegalArgumentException("Domain already in use: " + domain.getDomain());
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
