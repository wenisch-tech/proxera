package tech.wenisch.proxera.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationConfigTest {

    @Test
    void shouldRegisterAllJavaBasedFlywayMigrationsExplicitly() {
        Set<String> discoveredMigrations = findJavaMigrationsOnClasspath();
        Set<String> registeredMigrations = Arrays.stream(FlywayMigrationConfig.javaMigrations())
                .map(migration -> migration.getClass().getName())
                .collect(Collectors.toSet());

        assertThat(registeredMigrations)
                .as("Any Java-based Flyway migration must be registered explicitly for native images")
                .containsExactlyInAnyOrderElementsOf(discoveredMigrations);
    }

    private Set<String> findJavaMigrationsOnClasspath() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(JavaMigration.class));

        return scanner.findCandidateComponents("db.migration").stream()
                .map(candidate -> candidate.getBeanClassName())
                .collect(Collectors.toSet());
    }
}
