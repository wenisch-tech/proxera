package tech.wenisch.proxera.service;

import org.springframework.stereotype.Service;

import tech.wenisch.proxera.domain.Settings;
import tech.wenisch.proxera.repository.SettingsRepository;

@Service
public class SettingsService {

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public Settings get() {
        return settingsRepository.findById(1L)
                .orElseGet(() -> settingsRepository.save(new Settings()));
    }

    public void save(Settings settings) {
        settings.setId(1L);
        settingsRepository.save(settings);
    }
}
