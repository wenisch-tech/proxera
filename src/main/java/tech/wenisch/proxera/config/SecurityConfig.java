package tech.wenisch.proxera.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import tech.wenisch.proxera.security.ApiKeyAuthFilter;
import tech.wenisch.proxera.security.ProxyHostRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final ProxyHostRequestMatcher proxyHostRequestMatcher;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter, ProxyHostRequestMatcher proxyHostRequestMatcher) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.proxyHostRequestMatcher = proxyHostRequestMatcher;
    }

    /**
     * Security chain for admin paths. Matched by path prefix so it takes
     * priority (Order 1) over the open proxy chain.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();

        return http
                .securityMatcher(new AndRequestMatcher(
                        proxyHostRequestMatcher,
                        new OrRequestMatcher(
                                path.matcher("/admin/**"),
                                path.matcher("/login"),
                                path.matcher("/logout"),
                                path.matcher("/webjars/**"),
                                path.matcher("/css/**"),
                                path.matcher("/js/**"),
                                path.matcher("/actuator/**"),
                                path.matcher("/v3/api-docs/**"),
                                path.matcher("/swagger-ui/**"),
                                path.matcher("/api/**"),
                                path.matcher("/h2-console/**")
                        )
                ))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**",
                                "/actuator/**", "/h2-console/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/admin/", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    /**
     * Catch-all security chain for the reverse proxy. Everything not matched
     * by the admin chain arrives here and is permitted without authentication.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain proxySecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
