package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.proxera.domain.ApiKey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findAllByRevokedFalse();
    Optional<ApiKey> findByIdAndRevokedFalse(UUID id);
}
