package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;
import tech.wenisch.proxera.domain.RegistrationToken;
import tech.wenisch.proxera.repository.AgentRepository;
import tech.wenisch.proxera.repository.RegistrationTokenRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages registration tokens for the GitLab-runner-style agent registration flow.
 */
@Service
@Slf4j
public class RegistrationTokenService {

    private final RegistrationTokenRepository tokenRepository;
    private final AgentRepository agentRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationTokenService(RegistrationTokenRepository tokenRepository,
                                    AgentRepository agentRepository) {
        this.tokenRepository = tokenRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Generates a new registration token for the given agent.
     * Any existing token for this agent is replaced.
     *
     * @return the raw token (shown once — not stored in plain text)
     */
    @Transactional
    public String generate(UUID agentId) {
        tokenRepository.deleteByAgentId(agentId);

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        RegistrationToken token = RegistrationToken.builder()
                .agent(agent)
                .tokenHash(bcrypt.encode(rawToken))
                .build();
        tokenRepository.save(token);

        return rawToken;
    }

    /**
     * Validates a raw token against stored hashes.
     * Returns agent info if valid; empty if not.
     * Tokens remain valid for reconnects (used=true is set but not checked on reconnect).
     */
    @Transactional
    public Optional<ValidationResult> validate(String rawToken) {
        return agentRepository.findAll().stream()
                .flatMap(agent -> tokenRepository.findByAgentId(agent.getId()).stream()
                        .filter(t -> bcrypt.matches(rawToken, t.getTokenHash()))
                        .map(t -> {
                            if (!t.isUsed()) {
                                t.setUsed(true);
                                t.setUsedAt(LocalDateTime.now());
                                tokenRepository.save(t);
                                agent.setStatus(AgentStatus.REGISTERED);
                                agentRepository.save(agent);
                            }
                            return new ValidationResult(agent.getId(), agent.getName());
                        }))
                .findFirst();
    }

    public record ValidationResult(UUID agentId, String agentName) {}
}
