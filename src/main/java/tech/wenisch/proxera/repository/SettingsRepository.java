package tech.wenisch.proxera.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import tech.wenisch.proxera.domain.Settings;

public interface SettingsRepository extends JpaRepository<Settings, Long> {
}
