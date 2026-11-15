package com.alamano.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alamano.gateway.location.LocationPublisher;
import com.alamano.gateway.presence.ConnectionLostPublisher;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.lang.reflect.Type;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * HU-Q3: accesos indebidos por WebSocket. Un cliente que intenta compartir ubicación como si
 * fuera barbero, o suscribirse a colas y servicios que no le pertenecen, recibe el aviso de
 * rechazo por su cola de errores, la conexión sigue abierta y nada se publica.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "logging.level.root=INFO"})
class ForbiddenAccessIntegrationTest {
    static KeyPair pair = generatePair();
    static WebSocketStompClient client;
    static ThreadPoolTaskScheduler scheduler;

    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired ActiveServiceRegistry activeServices;
    @MockitoBean LocationPublisher locationPublisher;
    @MockitoBean ConnectionLostPublisher connectionLostPublisher;

    @BeforeAll
    static void setup() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) client.stop();
        if (scheduler != null) scheduler.shutdown();
    }

    @BeforeEach
    void registerService() {
        activeServices.apply("s-9", "pro-9", "cliente-9", "EN_ROUTE", 100);
    }

    @TestConfiguration
    static class JwtTestConfig {
        @Bean @Primary
        JwtDecoder testDecoder() {
            return NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) pair.getPublic()).build();
        }

        @Bean
        JwtEncoder testEncoder() {
            var jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).build();
            return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
                    new com.nimbusds.jose.jwk.JWKSet(jwk)));
        }
    }

    @Test
    void unClienteNoPuedeCompartirUbicacionComoBarbero() throws Exception {
        StompSession cliente = connect(token("cliente-1", "CLIENT"));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(cliente);

        cliente.send("/app/location", Map.of("latitude", 4.65, "longitude", -74.06));

        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error, "el cliente debe recibir el aviso de rechazo");
        assertEquals("forbidden", error.get("error"));
        verifyNoInteractions(locationPublisher);
        assertTrue(cliente.isConnected());
        cliente.disconnect();
    }

    @Test
    void unTokenSinRolTampocoPuedeCompartirUbicacion() throws Exception {
        StompSession anonimo = connect(token("alguien", null));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(anonimo);

        anonimo.send("/app/location", Map.of("latitude", 4.65, "longitude", -74.06));

        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("forbidden", error.get("error"));
        verifyNoInteractions(locationPublisher);
        anonimo.disconnect();
    }

    @Test
    void nadiePuedeSuscribirseAColasDeOtrosNiATopicsArbitrarios() throws Exception {
        StompSession cliente = connect(token("cliente-1", "CLIENT"));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(cliente);

        cliente.subscribe("/queue/services", handler(new LinkedBlockingQueue<>()));
        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("subscription_denied", error.get("error"));

        cliente.subscribe("/topic/admin", handler(new LinkedBlockingQueue<>()));
        error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("subscription_denied", error.get("error"));

        assertTrue(cliente.isConnected(), "rechazar una suscripción no cierra la sesión");
        cliente.disconnect();
    }

    @Test
    void unServicioAjenoOInexistenteNoSePuedeSeguir() throws Exception {
        StompSession intruso = connect(token("cliente-2", "CLIENT"));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(intruso);

        intruso.subscribe("/topic/service.s-9", handler(new LinkedBlockingQueue<>()));
        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("subscription_denied", error.get("error"));

        intruso.subscribe("/topic/service.no-existe", handler(new LinkedBlockingQueue<>()));
        error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("service_unknown", error.get("error"));
        intruso.disconnect();
    }

    private BlockingQueue<Map<String, Object>> subscribeErrors(StompSession session) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/errors", handler(queue));
        return queue;
    }

    private StompFrameHandler handler(BlockingQueue<Map<String, Object>> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @SuppressWarnings("unchecked")
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        };
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, headers,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private String token(String subject, String role) {
        Instant now = Instant.now();
        var builder = JwtClaimsSet.builder().subject(subject).issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60));
        if (role != null) builder.claim("role", role);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build()))
                .getTokenValue();
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
