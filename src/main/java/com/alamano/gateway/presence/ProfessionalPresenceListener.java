package com.alamano.gateway.presence;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class ProfessionalPresenceListener {
    private static final Logger log = LoggerFactory.getLogger(ProfessionalPresenceListener.class);
    private static final String PROFESSIONAL_ROLE = "ROLE_PROFESSIONAL";

    private final ProfessionalSessionRegistry sessionRegistry;
    private final ConnectionLostPublisher connectionLostPublisher;
    private final ConcurrentHashMap<String, String> professionalBySession = new ConcurrentHashMap<>();

    public ProfessionalPresenceListener(
            ProfessionalSessionRegistry sessionRegistry, ConnectionLostPublisher connectionLostPublisher) {
        this.sessionRegistry = sessionRegistry;
        this.connectionLostPublisher = connectionLostPublisher;
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        JwtAuthenticationToken user = professionalUser(principal(event.getUser(), event.getMessage().getHeaders()));
        if (user == null) return;
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId == null) return;

        String professionalId = user.getToken().getSubject();
        sessionRegistry.register(professionalId, sessionId);
        professionalBySession.put(sessionId, professionalId);
        log.info("Sesión de vendedor registrada: {} (sesiones activas: {}).", professionalId,
                sessionRegistry.sessionCount(professionalId));
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        JwtAuthenticationToken user = professionalUser(principal(event.getUser(), event.getMessage().getHeaders()));
        String professionalId = user == null ? professionalBySession.remove(sessionId)
                : user.getToken().getSubject();
        if (professionalId == null) return;
        professionalBySession.remove(sessionId);
        boolean lastSession = sessionRegistry.unregister(professionalId, sessionId);
        log.info("Sesión cerrada para el vendedor {} (sesiones activas: {}).", professionalId,
                sessionRegistry.sessionCount(professionalId));
        if (lastSession) {
            log.info("Conexión perdida del vendedor {}: se publica professional.connection.lost", professionalId);
            connectionLostPublisher.publish(professionalId, Instant.now());
        }
    }

    private java.security.Principal principal(java.security.Principal eventUser,
            org.springframework.messaging.MessageHeaders headers) {
        return eventUser != null ? eventUser : SimpMessageHeaderAccessor.getUser(headers);
    }

    private JwtAuthenticationToken professionalUser(java.security.Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken authentication)) return null;
        boolean professional = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PROFESSIONAL_ROLE::equals);
        return professional ? authentication : null;
    }
}
