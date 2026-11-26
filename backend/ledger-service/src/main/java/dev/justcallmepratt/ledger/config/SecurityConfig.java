package dev.justcallmepratt.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Phase 1 security (local and CI only, see ARCHITECTURE.md "Security (phase 1 scope)"): the API
 * is open, everything else except health/info/prometheus and the API docs is denied, CORS is
 * limited to the web origin, and responses carry security headers. OAuth2/JWT and rate limiting
 * are phase 2 and must land before any public deployment.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection defends cookie-authenticated browser sessions. This API is
                // stateless JSON with no cookies or session, so there is nothing for a forged
                // cross-site request to ride on. Revisit when phase 2 adds authentication: a
                // bearer-token API stays CSRF-immune, a cookie-based one would not.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /** Only the configured web origin may call the API from a browser. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${ledger.web-origin:http://localhost:4200}") String webOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(webOrigin.split(",")).map(String::trim)
                .filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "Last-Event-ID"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
