package com.alamano.gateway.security;

import static org.junit.jupiter.api.Assertions.*;
import com.alamano.gateway.events.EventEnvelope;
import com.alamano.gateway.events.EventRouter;
import com.alamano.gateway.presence.ConnectionLostPublisher;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "logging.level.root=INFO"})
class WebSocketAuthTest {
    // Mensaje del frame ERROR que envía Spring cuando JwtChannelInterceptor rechaza el CONNECT.
    private static final String REJECTED_CONNECT_MESSAGE = "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]";

    static KeyPair pair = generatePair();
    static WebSocketStompClient client;
    static ThreadPoolTaskScheduler scheduler;
    @LocalServerPort int port;
    @Autowired EventRouter router;
    @Autowired JwtEncoder encoder;
    // Evita que la desconexión del vendedor de prueba intente publicar en un RabbitMQ real.
    @MockitoBean ConnectionLostPublisher connectionLostPublisher;
    private final AtomicReference<Throwable> clientError = new AtomicReference<>();
    private final AtomicReference<StompHeaders> errorFrame = new AtomicReference<>();

    @BeforeAll static void setup() throws Exception {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        scheduler = new ThreadPoolTaskScheduler(); scheduler.initialize();
        client.setTaskScheduler(scheduler);
    }
    private static KeyPair generatePair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @AfterAll static void cleanup() { if (client != null) client.stop(); if (scheduler != null) scheduler.shutdown(); }

    @TestConfiguration static class JwtTestConfig {
        @Bean @Primary JwtDecoder testDecoder() { return NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey)pair.getPublic()).build(); }
        @Bean JwtEncoder testEncoder() { return encoderFor(pair); }
    }

    private static JwtEncoder encoderFor(KeyPair keys) {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(
                new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey)keys.getPublic())
                        .privateKey(keys.getPrivate()).build())));
    }
    private String token(Instant expiry, JwtEncoder encoder) {
        var claims = JwtClaimsSet.builder().subject("seller-1").issuedAt(expiry.minus(Duration.ofMinutes(10))).expiresAt(expiry)
                .claim("role", "PROFESSIONAL").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    private StompSession connect(String token) throws Exception {
        var headers = new StompHeaders(); if (token != null) headers.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, headers,
                new StompSessionHandlerAdapter() {
                    // Solo se invoca para frames ERROR enviados por el servidor.
                    @Override public void handleFrame(StompHeaders headers, Object payload) { errorFrame.set(headers); }
                    @Override public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                            byte[] payload, Throwable exception) { clientError.set(exception); }
                    @Override public void handleTransportError(StompSession session, Throwable exception) { clientError.set(exception); }
                }).get(5, TimeUnit.SECONDS);
    }
    // Un rechazo real es un frame ERROR seguido del cierre de la conexión.
    // Si el servidor no responde, get() lanza TimeoutException y la prueba falla.
    private void assertConnectRejected(String token) {
        var exception = assertThrows(ExecutionException.class, () -> connect(token));
        assertInstanceOf(ConnectionLostException.class, exception.getCause());
        assertNotNull(errorFrame.get(), "El servidor debe enviar un frame ERROR antes de cerrar la conexión");
        assertEquals(REJECTED_CONNECT_MESSAGE, errorFrame.get().getFirst("message"));
    }

    @Test void validTokenConnectsSubscribesAndReceivesEvent() throws Exception {
        var session = connect(token(Instant.now().plusSeconds(60), encoder));
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session.subscribe(EventRouter.MAP_TOPIC, new StompFrameHandler() {
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) { return Map.class; }
            @SuppressWarnings("unchecked") public void handleFrame(StompHeaders headers, Object payload) { received.add((Map<String, Object>) payload); }
        });
        Thread.sleep(200);
        var event = new EventEnvelope("e1", "professional.online", 1, Instant.now(), "c1", JsonNodeFactory.instance.objectNode());
        Map<String, Object> result = null;
        for (int i = 0; i < 20 && result == null; i++) { router.route(event); result = received.poll(100, TimeUnit.MILLISECONDS); }
        assertNotNull(result, () -> "El cliente debe recibir el evento por STOMP: " + clientError.get());
        assertEquals("e1", result.get("eventId"));
        session.disconnect();
    }
    @Test void missingTokenFails() { assertConnectRejected(null); }
    @Test void invalidSignatureFails() throws Exception {
        var otherGen = KeyPairGenerator.getInstance("RSA"); otherGen.initialize(2048);
        assertConnectRejected(token(Instant.now().plusSeconds(60), encoderFor(otherGen.generateKeyPair())));
    }
    // Vencido 5 minutos: queda fuera de la tolerancia de reloj de 60 s de NimbusJwtDecoder.
    @Test void expiredTokenFails() { assertConnectRejected(token(Instant.now().minus(Duration.ofMinutes(5)), encoder)); }
}
