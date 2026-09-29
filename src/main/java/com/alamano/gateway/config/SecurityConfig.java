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
    @Bean
    RSAPublicKey jwtPublicKey(@Value("${alamano.security.jwt-public-key}") org.springframework.core.io.Resource keyResource)
            throws Exception {
        try (var input = keyResource.getInputStream()) {
            String pem = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            var spec = new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pem));
            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }

    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey key) {
        return NimbusJwtDecoder.withPublicKey(key).build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/ws", "/ws/**", "/actuator/health", "/actuator/health/**")
                        .permitAll().anyRequest().denyAll()).build();
    }
}
