package com.alamano.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class WebSocketSessionMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WebSocketSessionMetrics metrics = new WebSocketSessionMetrics(registry);

    @Test
    void cuentaConexionesYDesconexiones() {
        metrics.onConnected(new SessionConnectedEvent(this, frame("s1")));
        metrics.onConnected(new SessionConnectedEvent(this, frame("s2")));
        assertEquals(2, gauge());

        metrics.onDisconnect(new SessionDisconnectEvent(this, frame("s1"), "s1", CloseStatus.NORMAL));
        assertEquals(1, gauge());
    }

    @Test
    void nuncaBajaDeCero() {
        metrics.onDisconnect(new SessionDisconnectEvent(this, frame("x"), "x", CloseStatus.GOING_AWAY));
        assertEquals(0, gauge());
        assertEquals(0, metrics.active());
    }

    private int gauge() {
        return (int) registry.get(WebSocketSessionMetrics.METRIC).gauge().value();
    }

    private static Message<byte[]> frame(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
