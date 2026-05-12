package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.proxera.domain.RegistrationToken;

import java.util.List;
import java.util.UUID;

public interface RegistrationTokenRepository extends JpaRepository<RegistrationToken, UUID> {
    List<RegistrationToken> findByClientId(UUID clientId);
    void deleteByClientId(UUID clientId);
}
