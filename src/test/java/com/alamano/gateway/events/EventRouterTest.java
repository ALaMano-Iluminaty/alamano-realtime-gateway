package com.alamano.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alamano.gateway.services.ActiveService;
import com.alamano.gateway.services.ActiveServiceRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class EventRouterTest {
    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final ActiveServiceRegistry registry = new ActiveServiceRegistry();
    private final EventRouter router = new EventRouter(template, registry);

    @Test
    void serviceStatusChangedUpdatesRegistryWithoutSending() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("serviceId", "s-1").put("professionalId", "pro-1").put("clientId", "client-1")
                .put("previousStatus", "RESERVED").put("status", "EN_ROUTE").put("version", 2);
        router.route(event("service.status.changed", payload));
        assertEquals(new ActiveService("s-1", "pro-1", "client-1", "EN_ROUTE", 2),
                registry.findByProfessional("pro-1").orElseThrow());
        verifyNoInteractions(template);
    }

    @Test
    void incompleteServiceStatusChangedIsIgnored() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("serviceId", "s-1").put("professionalId", "pro-1").put("status", "EN_ROUTE");
        router.route(event("service.status.changed", payload));
        assertTrue(registry.findByService("s-1").isEmpty());
        verifyNoInteractions(template);
    }

    @Test
    void trackingUpdatedIsSentToServiceTopic() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("serviceId", "s-1").put("professionalId", "pro-1")
                .put("latitude", 4.7826).put("longitude", -74.0435).put("etaSeconds", 420)
                .put("recordedAt", "2026-10-07T12:00:00Z");
        EventEnvelope tracking = event("tracking.updated", payload);
        router.route(tracking);
        verify(template).convertAndSend("/topic/service.s-1", tracking);
    }

    @Test
    void trackingUpdatedWithoutServiceIdIsNotSent() {
        router.route(event("tracking.updated", JsonNodeFactory.instance.objectNode().put("professionalId", "pro-1")));
        verifyNoInteractions(template);
    }

    private static EventEnvelope event(String type, ObjectNode payload) {
        return new EventEnvelope("e-1", type, 1, Instant.now(), "corr-1", payload);
    }
}
