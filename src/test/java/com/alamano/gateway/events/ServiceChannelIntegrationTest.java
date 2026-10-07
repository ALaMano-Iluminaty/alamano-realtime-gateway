package com.alamano.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
class ServiceChannelIntegrationTest {
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
            return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
                    new com.nimbusds.jose.jwk.JWKSet(jwk)));
        }
    }

    @Test
    void statusChangeIsDeliveredOnlyToSubscribersOfItsService() throws Exception {
        activeServices.apply("s-1", "pro-1", "client-1", "RESERVED", 1);
        activeServices.apply("s-2", "pro-2", "client-2", "RESERVED", 1);
        StompSession firstClient = connect(token("client-1", "CLIENT"));
        StompSession secondClient = connect(token("client-2", "CLIENT"));
        BlockingQueue<Map<String, Object>> firstMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> secondMessages = new LinkedBlockingQueue<>();
        firstClient.subscribe("/topic/service.s-1", handler(firstMessages));
        secondClient.subscribe("/topic/service.s-2", handler(secondMessages));
        Thread.sleep(250);

        Map<String, Object> received = null;
        for (int version = 2; version < 22 && received == null; version++) {
            var payload = JsonNodeFactory.instance.objectNode().put("serviceId", "s-1")
                    .put("professionalId", "pro-1").put("clientId", "client-1")
                    .put("previousStatus", version == 2 ? "RESERVED" : "EN_ROUTE")
                    .put("status", "EN_ROUTE").put("version", version);
            router.route(new EventEnvelope("status-" + version, "service.status.changed", 1,
                    Instant.now(), "corr-1", payload));
            received = firstMessages.poll(100, TimeUnit.MILLISECONDS);
        }

        assertNotNull(received, "El cliente del servicio s-1 debe recibir el cambio de estado");
        assertEquals("service.status.changed", received.get("type"));
        assertEquals("s-1", ((Map<?, ?>) received.get("payload")).get("serviceId"));
        assertEquals(0, secondMessages.size(), "El cliente de s-2 no debe recibir eventos de s-1");
        firstClient.disconnect();
        secondClient.disconnect();
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
