package com.alamano.gateway.config;

import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // Spring Security convierte la ubicación del PEM (classpath:, file:) en RSAPublicKey.
    // El decodificador valida la firma RS256 y la expiración del token.
    @Bean
    JwtDecoder jwtDecoder(@Value("${alamano.security.jwt-public-key}") RSAPublicKey key) {
        return NimbusJwtDecoder.withPublicKey(key).build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/ws", "/ws/**", "/actuator/health", "/actuator/health/**")
                        .permitAll().anyRequest().denyAll()).build();
    }
}
