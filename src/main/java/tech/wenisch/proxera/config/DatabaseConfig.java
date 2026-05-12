package tech.wenisch.proxera.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Provides the application DataSource.
 *
 * Default (no DB_HOST set): in-memory H2 database in PostgreSQL compatibility mode.
 * Production (DB_HOST set): PostgreSQL using DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD.
 */
@Configuration
@Slf4j
public class DatabaseConfig {

    @Value("${DB_HOST:}")
    private String dbHost;

    @Value("${DB_PORT:5432}")
    private String dbPort;

    @Value("${DB_NAME:proxera}")
    private String dbName;

    @Value("${DB_USER:proxera}")
    private String dbUser;

    @Value("${DB_PASSWORD:}")
    private String dbPassword;

    @Bean
    public DataSource dataSource() {
        if (dbHost != null && !dbHost.isBlank()) {
            String url = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
            log.info("Using PostgreSQL datasource: {}", url);
            return DataSourceBuilder.create()
                    .url(url)
                    .username(dbUser)
                    .password(dbPassword)
                    .driverClassName("org.postgresql.Driver")
                    .build();
        }

        log.info("DB_HOST not set — using embedded H2 datasource (development mode)");
        return DataSourceBuilder.create()
                .url("jdbc:h2:mem:proxera;MODE=PostgreSQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=value")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }
}
