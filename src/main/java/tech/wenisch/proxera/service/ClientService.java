package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.Client;
import tech.wenisch.proxera.domain.ClientStatus;
import tech.wenisch.proxera.domain.RegistrationToken;
import tech.wenisch.proxera.repository.ClientRepository;
import tech.wenisch.proxera.repository.RegistrationTokenRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ClientService {

    private final ClientRepository clientRepository;
    private final RegistrationTokenRepository tokenRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public ClientService(ClientRepository clientRepository,
                         RegistrationTokenRepository tokenRepository) {
        this.clientRepository = clientRepository;
        this.tokenRepository = tokenRepository;
    }

    public List<Client> findAll() {
        return clientRepository.findAll();
    }

    public Optional<Client> findById(UUID id) {
        return clientRepository.findById(id);
    }

    @Transactional
    public Client create(String name) {
        if (clientRepository.existsByName(name)) {
            throw new IllegalArgumentException("Client name already exists: " + name);
        }
        return clientRepository.save(Client.builder().name(name).build());
    }

    @Transactional
    public void delete(UUID id) {
        clientRepository.deleteById(id);
    }

    @Transactional
    public void markConnected(UUID clientId) {
        clientRepository.findById(clientId).ifPresent(client -> {
            client.setStatus(ClientStatus.CONNECTED);
            client.setLastSeenAt(LocalDateTime.now());
            clientRepository.save(client);
        });
    }

    @Transactional
    public void markDisconnected(UUID clientId) {
        clientRepository.findById(clientId).ifPresent(client -> {
            client.setStatus(ClientStatus.DISCONNECTED);
            client.setConnectedPodId(null);
            clientRepository.save(client);
        });
    }

    public long countConnected() {
        return clientRepository.findAllByStatus(ClientStatus.CONNECTED).size();
    }
}
