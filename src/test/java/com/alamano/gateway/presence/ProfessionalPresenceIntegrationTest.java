package com.alamano.gateway.presence;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "logging.level.root=INFO"})
class ProfessionalPresenceIntegrationTest {
    static KeyPair pair = generatePair();
    static WebSocketStompClient client;
    static ThreadPoolTaskScheduler scheduler;

    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired ProfessionalSessionRegistry sessionRegistry;
    @MockitoBean ConnectionLostPublisher publisher;

    @BeforeAll
    static void setup() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) client.stop();
        if (scheduler != null) scheduler.shutdown();
    }

    @TestConfiguration
    static class JwtTestConfig {
        @Bean @Primary
        org.springframework.security.oauth2.jwt.JwtDecoder testDecoder() {
            return org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                    .withPublicKey((java.security.interfaces.RSAPublicKey) pair.getPublic()).build();
        }

        @Bean
        JwtEncoder testEncoder() {
            var jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).build();
            return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk)));
        }
    }

    @Test
    void disconnectingProfessionalPublishesConnectionLost() throws Exception {
        StompSession session = connect(token("pro-1", "PROFESSIONAL"));
        assertNotNull(session);
        org.junit.jupiter.api.Assertions.assertEquals(1, sessionRegistry.sessionCount("pro-1"));
        session.disconnect();
        verify(publisher, timeout(TimeUnit.SECONDS.toMillis(3)))
                .publish(eq("pro-1"), any(Instant.class));
    }

    @Test
    void disconnectingClientDoesNotPublishConnectionLost() throws Exception {
        StompSession session = connect(token("client-1", "CLIENT"));
        assertNotNull(session);
        session.disconnect();
        Thread.sleep(300);
        verifyNoInteractions(publisher);
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, headers,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private String token(String subject, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(subject).issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60))
                .claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private static KeyPair generatePair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
