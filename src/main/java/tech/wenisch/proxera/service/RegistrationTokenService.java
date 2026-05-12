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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages registration tokens for the GitLab-runner-style client registration flow.
 */
@Service
@Slf4j
public class RegistrationTokenService {

    private final RegistrationTokenRepository tokenRepository;
    private final ClientRepository clientRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationTokenService(RegistrationTokenRepository tokenRepository,
                                    ClientRepository clientRepository) {
        this.tokenRepository = tokenRepository;
        this.clientRepository = clientRepository;
    }

    /**
     * Generates a new registration token for the given client.
     * Any existing token for this client is replaced.
     *
     * @return the raw token (shown once — not stored in plain text)
     */
    @Transactional
    public String generate(UUID clientId) {
        tokenRepository.deleteByClientId(clientId);

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));

        RegistrationToken token = RegistrationToken.builder()
                .client(client)
                .tokenHash(bcrypt.encode(rawToken))
                .build();
        tokenRepository.save(token);

        return rawToken;
    }

    /**
     * Validates a raw token against stored hashes.
     * Returns client info if valid; empty if not.
     * Tokens remain valid for reconnects (used=true is set but not checked on reconnect).
     */
    @Transactional
    public Optional<ValidationResult> validate(String rawToken) {
        return clientRepository.findAll().stream()
                .flatMap(client -> tokenRepository.findByClientId(client.getId()).stream()
                        .filter(t -> bcrypt.matches(rawToken, t.getTokenHash()))
                        .map(t -> {
                            if (!t.isUsed()) {
                                t.setUsed(true);
                                t.setUsedAt(LocalDateTime.now());
                                tokenRepository.save(t);
                                client.setStatus(ClientStatus.REGISTERED);
                                clientRepository.save(client);
                            }
                            return new ValidationResult(client.getId(), client.getName());
                        }))
                .findFirst();
    }

    public record ValidationResult(UUID clientId, String clientName) {}
}
