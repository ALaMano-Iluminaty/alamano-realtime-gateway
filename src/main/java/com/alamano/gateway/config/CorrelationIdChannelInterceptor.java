package com.alamano.gateway.config;

import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Propaga el correlationId desde el header STOMP al MDC.
 * En CONNECT genera uno si el cliente no envía X-Correlation-Id.
 * En los demás frames lo recupera de la sesión STOMP.
 */
@Component
public class CorrelationIdChannelInterceptor implements ChannelInterceptor {

    static final String STOMP_HEADER = "X-Correlation-Id";
    static final String SESSION_ATTR = "correlationId";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (accessor.getCommand() == StompCommand.CONNECT) {
            List<String> header = accessor.getNativeHeader(STOMP_HEADER);
            String correlationId = (header != null && !header.isEmpty()) ? header.get(0) : UUID.randomUUID().toString();
            accessor.getSessionAttributes().put(SESSION_ATTR, correlationId);
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        } else {
            var attrs = accessor.getSessionAttributes();
            if (attrs != null) {
                String correlationId = (String) attrs.get(SESSION_ATTR);
                if (correlationId != null) MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
}
