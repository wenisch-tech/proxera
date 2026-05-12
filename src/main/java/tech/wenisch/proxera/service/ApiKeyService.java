package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.ApiKey;
import tech.wenisch.proxera.repository.ApiKeyRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Generates a new API key. Returns the raw key (shown once).
     */
    @Transactional
    public String generate(String name) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = "pk_" + HexFormat.of().formatHex(bytes);

        ApiKey key = ApiKey.builder()
                .name(name)
                .keyHash(bcrypt.encode(raw))
                .build();
        apiKeyRepository.save(key);
        return raw;
    }

    public List<ApiKey> findAll() {
        return apiKeyRepository.findAll();
    }

    @Transactional
    public void revoke(UUID id) {
        apiKeyRepository.deleteById(id);
    }

    /**
     * Validates a raw API key against stored hashes. O(n) but acceptable for typical key counts.
     */
    public boolean isValid(String rawKey) {
        return apiKeyRepository.findAll().stream()
                .anyMatch(k -> bcrypt.matches(rawKey, k.getKeyHash()));
    }

    /**
     * Records last-used timestamp for a validated key.
     */
    @Transactional
    public void recordUsage(String rawKey) {
        apiKeyRepository.findAll().stream()
                .filter(k -> bcrypt.matches(rawKey, k.getKeyHash()))
                .findFirst()
                .ifPresent(k -> {
                    k.setLastUsedAt(LocalDateTime.now());
                    apiKeyRepository.save(k);
                });
    }
}
