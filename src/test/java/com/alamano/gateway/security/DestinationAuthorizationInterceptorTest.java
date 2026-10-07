package com.alamano.gateway.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.alamano.gateway.errors.UserErrorNotifier;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class DestinationAuthorizationInterceptorTest {
    private final ActiveServiceRegistry registry = new ActiveServiceRegistry();
    private final UserErrorNotifier notifier = mock(UserErrorNotifier.class);
    private final DestinationAuthorizationInterceptor interceptor =
            new DestinationAuthorizationInterceptor(registry, notifier);

    @BeforeEach
    void registerService() {
        registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 1);
    }

    @Test
    void serviceClientAndProfessionalCanSubscribe() {
        org.junit.jupiter.api.Assertions.assertNotNull(pass(subscribe("/topic/service.s-1", user("client-1", "CLIENT"))));
        org.junit.jupiter.api.Assertions.assertNotNull(pass(subscribe("/topic/service.s-1", user("pro-1", "PROFESSIONAL"))));
    }

    @Test
    void unrelatedUserIsRejectedWithNotice() {
        assertNull(pass(subscribe("/topic/service.s-1", user("other", "CLIENT"))));
        verify(notifier).notify("other", "subscription_denied", "No puedes seguir un servicio que no es tuyo",
                "/topic/service.s-1");
    }

    @Test
    void unknownServiceIsRejectedWithNotice() {
        assertNull(pass(subscribe("/topic/service.missing", user("client-1", "CLIENT"))));
        verify(notifier).notify("client-1", "service_unknown",
                "El servicio no existe o todavía no está disponible; vuelve a intentarlo en unos segundos",
                "/topic/service.missing");
    }

    @Test
    void mapAndUserErrorSubscriptionsAreAllowed() {
        var client = user("client-1", "CLIENT");
        org.junit.jupiter.api.Assertions.assertNotNull(pass(subscribe("/topic/map", client)));
        org.junit.jupiter.api.Assertions.assertNotNull(pass(subscribe("/user/queue/errors", client)));
    }

    @Test
    void sendToAppIsAllowedButDirectBrokerSendIsRejected() {
        var professional = user("pro-1", "PROFESSIONAL");
        org.junit.jupiter.api.Assertions.assertNotNull(pass(frame(StompCommand.SEND, "/app/location", professional)));
        assertNull(pass(frame(StompCommand.SEND, "/topic/service.s-1", professional)));
        verify(notifier).notify("pro-1", "forbidden", "No puedes enviar mensajes directamente a este destino",
                "/topic/service.s-1");
    }

    @Test
    void otherBrokerDestinationsAreRejected() {
        var client = user("client-1", "CLIENT");
        assertNull(pass(subscribe("/topic/other", client)));
        assertNull(pass(subscribe("/queue/errors", client)));
    }

    @Test
    void unsubscribeAndDisconnectPassUnchanged() {
        var client = user("client-1", "CLIENT");
        Message<?> unsubscribe = frame(StompCommand.UNSUBSCRIBE, null, client);
        Message<?> disconnect = frame(StompCommand.DISCONNECT, null, client);
        org.junit.jupiter.api.Assertions.assertSame(unsubscribe, pass(unsubscribe));
        org.junit.jupiter.api.Assertions.assertSame(disconnect, pass(disconnect));
    }

    private Message<?> pass(Message<?> message) {
        return interceptor.preSend(message, null);
    }

    private static Message<byte[]> subscribe(String destination, JwtAuthenticationToken user) {
        return frame(StompCommand.SUBSCRIBE, destination, user);
    }

    private static Message<byte[]> frame(StompCommand command, String destination, JwtAuthenticationToken user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(user);
        accessor.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static JwtAuthenticationToken user(String subject, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject)
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60)).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), subject);
    }
}
