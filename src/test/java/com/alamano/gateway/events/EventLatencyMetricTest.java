package com.alamano.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.alamano.gateway.services.ActiveServiceRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** HU-Q6/Q10: cada evento repartido registra cuanto tardo en llegar desde el Core. */
class EventLatencyMetricTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventRouter router = new EventRouter(mock(SimpMessagingTemplate.class), new ActiveServiceRegistry(), registry);

    @Test
    void registraLaLatenciaPorTipoDeEvento() {
        router.route(event("professional.online", Instant.now().minus(Duration.ofMillis(250))));

        Timer timer = registry.get(EventRouter.EVENT_LATENCY_METRIC).tag("type", "professional.online").timer();
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 250, "latencia medida: " + timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void unRelojAdelantadoSeRegistraComoCero() {
        router.route(event("professional.online", Instant.now().plus(Duration.ofMinutes(5))));

        Timer timer = registry.get(EventRouter.EVENT_LATENCY_METRIC).timer();
        assertEquals(1, timer.count());
        assertEquals(0, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void sinMarcaDeTiempoNoRegistraNada() {
        router.route(event("professional.online", null));
        assertNull(registry.find(EventRouter.EVENT_LATENCY_METRIC).timer());
    }

    private static EventEnvelope event(String type, Instant occurredAt) {
        return new EventEnvelope("e-1", type, 1, occurredAt, "c-1", JsonNodeFactory.instance.objectNode());
    }
}
