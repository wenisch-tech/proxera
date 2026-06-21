package tech.wenisch.proxera.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;
import tech.wenisch.proxera.repository.AgentRepository;
import tech.wenisch.proxera.repository.RegistrationTokenRepository;

@Service
@Slf4j
public class AgentService {

    private final AgentRepository agentRepository;
    private final RegistrationTokenRepository tokenRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AgentService(AgentRepository agentRepository,
                        RegistrationTokenRepository tokenRepository) {
        this.agentRepository = agentRepository;
        this.tokenRepository = tokenRepository;
    }

    public List<Agent> findAll() {
        return agentRepository.findAll();
    }

    public Optional<Agent> findById(UUID id) {
        return agentRepository.findById(id);
    }

    public boolean existsById(UUID id) {
        return agentRepository.existsById(id);
    }

    @Transactional
    public Agent create(String name) {
        if (agentRepository.existsByName(name)) {
            throw new IllegalArgumentException("Agent name already exists: " + name);
        }
        return agentRepository.save(Agent.builder().name(name).build());
    }

    @Transactional
    public void rename(UUID id, String newName) {
        if (agentRepository.existsByName(newName)) {
            throw new IllegalArgumentException("Agent name already exists: " + newName);
        }
        agentRepository.findById(id).ifPresent(agent -> {
            agent.setName(newName);
            agentRepository.save(agent);
        });
    }

    @Transactional
    public void delete(UUID id) {
        agentRepository.deleteById(id);
    }

    @Transactional
    public void markConnected(UUID agentId, String remoteIp) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.CONNECTED);
            agent.setLastSeenAt(LocalDateTime.now());
            agent.setRemoteIp(remoteIp);
            agent.setConnectedPodId(System.getenv().getOrDefault("HOSTNAME", null));
            agentRepository.save(agent);
        });
    }

    @Transactional
    public void markDisconnected(UUID agentId) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.DISCONNECTED);
            agent.setConnectedPodId(null);
            agent.setRemoteIp(null);
            agentRepository.save(agent);
        });
    }

    public long countConnected() {
        return agentRepository.findAllByStatus(AgentStatus.CONNECTED).size();
    }
}
