package com.alamano.gateway.presence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class ProfessionalPresenceListenerTest {
    @Test
    void professionalConnectAndDisconnectPublishesOnce() {
        var registry = new ProfessionalSessionRegistry();
        var publisher = mock(ConnectionLostPublisher.class);
        var listener = new ProfessionalPresenceListener(registry, publisher);
        JwtAuthenticationToken user = user("pro-1", "PROFESSIONAL");
        listener.onSessionConnected(connected("session-1", user));
        listener.onSessionDisconnect(disconnected("session-1"));
        verify(publisher).publish(eq("pro-1"), any(Instant.class));
    }

    @Test
    void closingOneOfTwoSessionsDoesNotPublishUntilLastCloses() {
        var registry = new ProfessionalSessionRegistry();
        var publisher = mock(ConnectionLostPublisher.class);
        var listener = new ProfessionalPresenceListener(registry, publisher);
        JwtAuthenticationToken user = user("pro-1", "PROFESSIONAL");
        listener.onSessionConnected(connected("session-1", user));
        listener.onSessionConnected(connected("session-2", user));
        listener.onSessionDisconnect(disconnected("session-1"));
        verifyNoInteractions(publisher);
        listener.onSessionDisconnect(disconnected("session-2"));
        verify(publisher).publish(eq("pro-1"), any(Instant.class));
    }

    @Test
    void repeatedDisconnectPublishesOnlyOnce() {
        var registry = new ProfessionalSessionRegistry();
        var publisher = mock(ConnectionLostPublisher.class);
        var listener = new ProfessionalPresenceListener(registry, publisher);
        JwtAuthenticationToken user = user("pro-1", "PROFESSIONAL");
        listener.onSessionConnected(connected("session-1", user));
        var disconnect = disconnected("session-1");
        listener.onSessionDisconnect(disconnect);
        listener.onSessionDisconnect(disconnect);
        verify(publisher, times(1)).publish(eq("pro-1"), any(Instant.class));
    }

    @Test
    void clientSessionIsIgnored() {
        var publisher = mock(ConnectionLostPublisher.class);
        var listener = new ProfessionalPresenceListener(new ProfessionalSessionRegistry(), publisher);
        JwtAuthenticationToken user = user("client-1", "CLIENT");
        listener.onSessionConnected(connected("session-1", user));
        listener.onSessionDisconnect(disconnected("session-1"));
        verifyNoInteractions(publisher);
    }

    // Toda desconexión llega sin usuario; si la sesión nunca se registró, no hay nada que publicar.
    @Test
    void disconnectOfUnregisteredSessionDoesNotThrowOrPublish() {
        var publisher = mock(ConnectionLostPublisher.class);
        var listener = new ProfessionalPresenceListener(new ProfessionalSessionRegistry(), publisher);
        assertDoesNotThrow(() -> listener.onSessionDisconnect(disconnected("session-1")));
        verifyNoInteractions(publisher);
    }

    private static JwtAuthenticationToken user(String subject, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject)
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60)).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), subject);
    }

    private static SessionConnectedEvent connected(String sessionId, Principal user) {
        return new SessionConnectedEvent(ProfessionalPresenceListenerTest.class,
                message(SimpMessageType.CONNECT_ACK, sessionId), user);
    }

    // Como en producción, el listener no depende del usuario al desconectar: solo usa el id de sesión.
    private static SessionDisconnectEvent disconnected(String sessionId) {
        return new SessionDisconnectEvent(ProfessionalPresenceListenerTest.class,
                message(SimpMessageType.DISCONNECT, sessionId), sessionId, CloseStatus.NORMAL, null);
    }

    private static Message<byte[]> message(SimpMessageType type, String sessionId) {
        var accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
