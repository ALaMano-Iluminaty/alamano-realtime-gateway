package com.alamano.gateway.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.alamano.gateway.events.EventEnvelope;
import com.alamano.gateway.events.EventRouter;
import com.alamano.gateway.presence.ConnectionLostPublisher;
import com.alamano.gateway.services.ActiveServiceRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "logging.level.root=INFO"})
class LocationIntegrationTest {
    static KeyPair pair = generatePair();
    static WebSocketStompClient client;
    static ThreadPoolTaskScheduler scheduler;

    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired ActiveServiceRegistry activeServices;
    @Autowired EventRouter router;
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
            return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk)));
        }
    }

    @Test
    void professionalLocationIsPublishedForActiveService() throws Exception {
        activeServices.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 1);
        StompSession session = connect(token("pro-1", "PROFESSIONAL"));

        session.send("/app/location", new LocationMessage(4.7826, -74.0435));

        verify(locationPublisher, timeout(TimeUnit.SECONDS.toMillis(3)))
                .publish(eq("pro-1"), eq("s-1"), eq(4.7826), eq(-74.0435), any(Instant.class));
        session.disconnect();
    }

    @Test
    void clientSubscribedToServiceReceivesTracking() throws Exception {
        StompSession session = connect(token("client-1", "CLIENT"));
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session.subscribe(EventRouter.SERVICE_TOPIC_PREFIX + "s-1", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @SuppressWarnings("unchecked")
            @Override public void handleFrame(StompHeaders headers, Object payload) { received.add((Map<String, Object>) payload); }
        });

        var payload = JsonNodeFactory.instance.objectNode().put("serviceId", "s-1").put("professionalId", "pro-1")
                .put("latitude", 4.7826).put("longitude", -74.0435).put("etaSeconds", 420)
                .put("recordedAt", "2026-10-07T12:00:00Z");
        var tracking = new EventEnvelope("t-1", "tracking.updated", 1, Instant.now(), "corr-1", payload);
        // La suscripción tarda unos milisegundos en registrarse: se reintenta el envío.
        Map<String, Object> result = null;
        for (int i = 0; i < 20 && result == null; i++) {
            router.route(tracking);
            result = received.poll(100, TimeUnit.MILLISECONDS);
        }
        assertNotNull(result, "El cliente debe recibir tracking.updated en /topic/service.s-1");
        assertEquals("tracking.updated", result.get("type"));
        assertEquals("t-1", result.get("eventId"));
        session.disconnect();
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
