package tech.wenisch.proxera.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import tech.wenisch.proxera.domain.AccessLog;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    List<AccessLog> findByRouteIdOrderByTimestampDesc(UUID routeId, Pageable pageable);

    List<AccessLog> findTop50ByOrderByTimestampDesc();

    @Query("SELECT a FROM AccessLog a ORDER BY a.timestamp DESC")
    List<AccessLog> findRecentLogs(Pageable pageable);

    @Query("SELECT COUNT(a) FROM AccessLog a WHERE a.routeId = :routeId AND a.timestamp >= :since")
    long countByRouteIdSince(UUID routeId, LocalDateTime since);

    @Modifying
    @Query("DELETE FROM AccessLog a WHERE a.timestamp < :before")
    int deleteByTimestampBefore(LocalDateTime before);
}
