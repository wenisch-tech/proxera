package tech.wenisch.proxera.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tech.wenisch.proxera.domain.RegistrationToken;

public interface RegistrationTokenRepository extends JpaRepository<RegistrationToken, UUID> {
    List<RegistrationToken> findByAgentId(UUID agentId);
    void deleteByAgentId(UUID agentId);
}
