package tech.wenisch.proxera.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.AccessLog;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.repository.AccessLogRepository;
import tech.wenisch.proxera.tunnel.ResponsePayload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class AccessLogService {

    private final AccessLogRepository accessLogRepository;

    public AccessLogService(AccessLogRepository accessLogRepository) {
        this.accessLogRepository = accessLogRepository;
    }

    @Transactional
    public AccessLog log(Route route, UUID agentId, HttpServletRequest request, ResponsePayload response) {
        AccessLog entry = AccessLog.builder()
                .routeId(route.getId())
                .agentId(agentId)
                .method(request.getMethod())
                .path(request.getRequestURI())
                .statusCode(response.status())
                .latencyMs(response.latencyMs())
                .remoteIp(getClientAddress(request))
                .build();
        return accessLogRepository.save(entry);
    }

    public List<AccessLog> getRecentForRoute(UUID routeId, int limit) {
        return accessLogRepository.findByRouteIdOrderByTimestampDesc(
                routeId, PageRequest.of(0, limit));
    }

    public List<AccessLog> getRecentGlobal(int limit) {
        return accessLogRepository.findRecentLogs(PageRequest.of(0, limit));
    }

    /**
     * Clean up log entries older than 30 days. Runs daily at midnight.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupOldLogs() {
        LocalDateTime before = LocalDateTime.now().minusDays(30);
        int deleted = accessLogRepository.deleteByTimestampBefore(before);
        log.info("Access log cleanup: deleted {} entries older than {}", deleted, before);
    }

    private String getClientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
