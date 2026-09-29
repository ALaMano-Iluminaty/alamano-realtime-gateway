package com.alamano.gateway.security;

import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class JwtChannelInterceptor implements ChannelInterceptor {
    private final JwtDecoder decoder;
    public JwtChannelInterceptor(JwtDecoder decoder) { this.decoder = decoder; }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.CONNECT) return message;
        List<String> headers = accessor.getNativeHeader("Authorization");
        if (headers == null || headers.isEmpty() || headers.get(0) == null || !headers.get(0).startsWith("Bearer ")) {
            throw new AuthenticationCredentialsNotFoundException("Falta el encabezado Authorization Bearer.");
        }
        try {
            Jwt jwt = decoder.decode(headers.get(0).substring(7));
            String role = jwt.getClaimAsString("role");
            var authorities = role == null || role.isBlank() ? List.<SimpleGrantedAuthority>of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
            accessor.setUser(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
            return org.springframework.messaging.support.MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        } catch (JwtException exception) {
            throw new BadCredentialsException("El token JWT no es válido o ha vencido.", exception);
        }
    }
}
