package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.proxera.domain.Client;
import tech.wenisch.proxera.domain.ClientStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    Optional<Client> findByName(String name);
    List<Client> findAllByStatus(ClientStatus status);
    boolean existsByName(String name);
}
