package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.repository.RouteRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an inbound HTTP request (host + path) to a Route.
 * Maintains an in-memory cache of domain → routes, invalidated on route changes.
 */
@Service
@Slf4j
public class RoutingService {

    private final RouteRepository routeRepository;
    // domain → sorted list of routes (longest pathPrefix first)
    private volatile Map<String, List<Route>> cache = null;

    public RoutingService(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    /**
     * Resolve a host + path to a Route.
     * Strips port from host if present. Matches longest pathPrefix first.
     */
    public Optional<Route> resolve(String host, String path) {
        if (host == null) return Optional.empty();

        // Strip port from host header (e.g. "example.com:8080" -> "example.com")
        String cleanHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        cleanHost = cleanHost.toLowerCase();

        Map<String, List<Route>> routeCache = getCache();
        List<Route> candidates = routeCache.get(cleanHost);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        // Find longest matching path prefix
        return candidates.stream()
                .filter(r -> {
                    String prefix = r.getPathPrefix();
                    return prefix == null || prefix.isBlank() || path.startsWith(prefix);
                })
                .max(Comparator.comparingInt(r ->
                        r.getPathPrefix() == null ? 0 : r.getPathPrefix().length()));
    }

    public void invalidateCache() {
        this.cache = null;
        log.debug("Route cache invalidated");
    }

    private Map<String, List<Route>> getCache() {
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = buildCache();
                }
            }
        }
        return cache;
    }

    private Map<String, List<Route>> buildCache() {
        Map<String, List<Route>> newCache = new ConcurrentHashMap<>();
        routeRepository.findAll().stream()
                .filter(Route::isEnabled)
                .forEach(route -> route.getDomains().forEach(d ->
                        newCache.computeIfAbsent(d.getDomain().toLowerCase(), k -> new java.util.ArrayList<>())
                                .add(route)));
        log.debug("Route cache built with {} domain entries", newCache.size());
        return newCache;
    }
}
