package com.alamano.gateway.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** HU-Q10: usuarios concurrentes conectados por WebSocket, por instancia. */
@Component
public class WebSocketSessionMetrics {
    public static final String METRIC = "alamano.ws.sessions.active";
    private final AtomicInteger active = new AtomicInteger();

    public WebSocketSessionMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, active, AtomicInteger::get).register(registry);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        active.incrementAndGet();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        active.updateAndGet(current -> Math.max(0, current - 1));
    }

    int active() {
        return active.get();
    }
}
