package tech.wenisch.proxera.config;

import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FlywayMigrationConfig {

    @Bean
    FlywayConfigurationCustomizer proxeraFlywayJavaMigrations() {
        return configuration -> configuration.javaMigrations(javaMigrations());
    }

    static JavaMigration[] javaMigrations() {
        return new JavaMigration[0];
    }
}
