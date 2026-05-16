package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
    Optional<Agent> findByName(String name);
    List<Agent> findAllByStatus(AgentStatus status);
    boolean existsByName(String name);
}
