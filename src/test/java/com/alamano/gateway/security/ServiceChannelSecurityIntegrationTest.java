package com.alamano.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alamano.gateway.events.EventEnvelope;
import com.alamano.gateway.events.EventRouter;
import com.alamano.gateway.location.LocationPublisher;
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
class ServiceChannelSecurityIntegrationTest {
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

    @BeforeEach
    void registerService() {
        activeServices.apply("s-1", "pro-1", "cliente-1", "EN_ROUTE", 100);
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
    void unauthorizedSubscriptionIsNotClosedAndCannotReceiveTracking() throws Exception {
        StompSession owner = connect(token("cliente-1", "CLIENT"));
        StompSession thirdParty = connect(token("cliente-2", "CLIENT"));
        BlockingQueue<Map<String, Object>> ownerEvents = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> thirdPartyEvents = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(thirdParty);
        owner.subscribe("/topic/service.s-1", handler(ownerEvents));
        thirdParty.subscribe("/topic/service.s-1", handler(thirdPartyEvents));

        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error, "El suscriptor ajeno debe recibir el aviso");
        assertEquals("subscription_denied", error.get("error"));
        assertTrueConnected(thirdParty);

        var payload = JsonNodeFactory.instance.objectNode().put("serviceId", "s-1").put("professionalId", "pro-1")
                .put("latitude", 4.65).put("longitude", -74.06).put("etaSeconds", 720)
                .put("recordedAt", "2026-10-07T12:00:00Z");
        EventEnvelope tracking = new EventEnvelope("tracking-1", "tracking.updated", 1, Instant.now(), "corr", payload);
        Map<String, Object> received = null;
        for (int i = 0; i < 20 && received == null; i++) {
            router.route(tracking);
            received = ownerEvents.poll(100, TimeUnit.MILLISECONDS);
        }
        assertNotNull(received, "El cliente propietario debe recibir tracking.updated");
        assertNull(thirdPartyEvents.poll(250, TimeUnit.MILLISECONDS));
        owner.disconnect();
        thirdParty.disconnect();
    }

    @Test
    void directSendToServiceTopicIsDroppedAndReported() throws Exception {
        StompSession owner = connect(token("cliente-1", "CLIENT"));
        BlockingQueue<Map<String, Object>> ownerEvents = new LinkedBlockingQueue<>();
        owner.subscribe("/topic/service.s-1", handler(ownerEvents));
        StompSession attacker = connect(token("cliente-2", "CLIENT"));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(attacker);
        attacker.send("/topic/service.s-1", Map.of("type", "tracking.updated", "payload", Map.of("fake", true)));

        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("forbidden", error.get("error"));
        assertNull(ownerEvents.poll(250, TimeUnit.MILLISECONDS));
        assertTrueConnected(attacker);
        owner.disconnect();
        attacker.disconnect();
    }

    @Test
    void professionalWithoutActiveServiceIsNotifiedAndLocationIsNotPublished() throws Exception {
        StompSession professional = connect(token("pro-2", "PROFESSIONAL"));
        BlockingQueue<Map<String, Object>> errors = subscribeErrors(professional);
        professional.send("/app/location", Map.of("latitude", 4.65, "longitude", -74.06));

        Map<String, Object> error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("no_active_service", error.get("error"));
        verifyNoInteractions(locationPublisher);
        professional.disconnect();
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

    private static void assertTrueConnected(StompSession session) {
        org.junit.jupiter.api.Assertions.assertTrue(session.isConnected(), "La conexión STOMP debe seguir abierta");
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
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
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
