package com.alamano.gateway.presence;

import java.security.Principal;
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

    // El usuario es el JwtAuthenticationToken que JwtChannelInterceptor validó en el CONNECT.
    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        JwtAuthenticationToken user = professionalUser(event.getUser());
        if (user == null) return;
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId == null) return;

        String professionalId = user.getToken().getSubject();
        sessionRegistry.register(professionalId, sessionId);
        professionalBySession.put(sessionId, professionalId);
        log.info("Sesión de vendedor registrada: {} (sesiones activas: {}).", professionalId,
                sessionRegistry.sessionCount(professionalId));
    }

    // Solo se usa el id de sesión: el vendedor sale del registro hecho en el CONNECTED.
    // remove es atómico, así que un SessionDisconnectEvent repetido devuelve null y no publica dos veces.
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String professionalId = professionalBySession.remove(sessionId);
        if (professionalId == null) return;
        boolean lastSession = sessionRegistry.unregister(professionalId, sessionId);
        log.info("Sesión cerrada para el vendedor {} (sesiones activas: {}).", professionalId,
                sessionRegistry.sessionCount(professionalId));
        if (lastSession) {
            log.info("Conexión perdida del vendedor {}: se publica professional.connection.lost", professionalId);
            connectionLostPublisher.publish(professionalId, Instant.now());
        }
    }

    private JwtAuthenticationToken professionalUser(Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken authentication)) return null;
        boolean professional = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PROFESSIONAL_ROLE::equals);
        return professional ? authentication : null;
    }
}
