package tech.wenisch.proxera.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.repository.RouteRepository;

/**
 * Resolves an inbound HTTP request (host + path) to a RouteDomain.
 * Each RouteDomain carries its own pathPrefix and stripPrefix alongside the target Route.
 * Maintains an in-memory cache of domain → RouteDomain entries, invalidated on route changes.
 */
@Service
@Slf4j
public class RoutingService {

    private final RouteRepository routeRepository;
    // domain → list of RouteDomain entries (each carries its own pathPrefix/stripPrefix)
    private volatile Map<String, List<RouteDomain>> cache = null;

    public RoutingService(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    /**
     * Resolve a host + path to a RouteDomain.
     * Strips port from host if present. Matches longest pathPrefix first.
     */
    public Optional<RouteDomain> resolve(String host, String path) {
        if (host == null) return Optional.empty();

        // Strip port from host header (e.g. "example.com:8080" -> "example.com")
        String cleanHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        cleanHost = cleanHost.toLowerCase();

        Map<String, List<RouteDomain>> routeCache = getCache();
        List<RouteDomain> candidates = routeCache.get(cleanHost);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        // Find longest matching path prefix
        return candidates.stream()
                .filter(rd -> {
                    String prefix = rd.getPathPrefix();
                    return prefix == null || prefix.isBlank() || path.startsWith(prefix);
                })
                .max(Comparator.comparingInt(rd ->
                        rd.getPathPrefix() == null ? 0 : rd.getPathPrefix().length()));
    }

    /**
     * Returns true if the given host (with optional port) is registered as a
     * proxy route domain. Used by the security layer to bypass admin-chain
     * matching for hosts that should always be proxied.
     */
    public boolean isKnownProxyDomain(String host) {
        if (host == null) return false;
        String cleanHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return getCache().containsKey(cleanHost.toLowerCase());
    }

    public void invalidateCache() {
        this.cache = null;
        log.debug("Route cache invalidated");
    }

    private Map<String, List<RouteDomain>> getCache() {
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = buildCache();
                }
            }
        }
        return cache;
    }

    private Map<String, List<RouteDomain>> buildCache() {
        Map<String, List<RouteDomain>> newCache = new ConcurrentHashMap<>();
        routeRepository.findAllWithAgent().stream()
                .filter(Route::isEnabled)
                .forEach(route -> route.getDomains().forEach(rd ->
                        newCache.computeIfAbsent(rd.getDomain().toLowerCase(), k -> new java.util.ArrayList<>())
                                .add(rd)));
        log.debug("Route cache built with {} domain entries", newCache.size());
        return newCache;
    }
}

