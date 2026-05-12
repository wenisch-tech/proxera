package tech.wenisch.proxera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.proxera.domain.User;
import tech.wenisch.proxera.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User create(String username, String plainPassword, String role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        // Store role without ROLE_ prefix (getAuthorities adds it)
        String normalizedRole = role.startsWith("ROLE_") ? role.substring(5) : role;
        return userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(plainPassword))
                .role(normalizedRole)
                .build());
    }

    @Transactional
    public void changePassword(UUID id, String newPlainPassword) {
        userRepository.findById(id).ifPresent(user -> {
            user.setPasswordHash(passwordEncoder.encode(newPlainPassword));
            userRepository.save(user);
        });
    }

    @Transactional
    public void delete(UUID id) {
        userRepository.deleteById(id);
    }

    /**
     * Creates the default admin user on first startup if no users exist.
     */
    @Transactional
    public void ensureDefaultAdminExists() {
        if (userRepository.count() == 0) {
            log.warn("No users found — creating default admin/admin. CHANGE THE PASSWORD!");
            create("admin", "admin", "ADMIN");
        }
    }
}
