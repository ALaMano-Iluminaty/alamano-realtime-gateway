package com.alamano.gateway.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class DomainEventListenerTest {
    private EventEnvelope event(String id, String type) {
        return new EventEnvelope(id, type, 1, Instant.now(), "corr", JsonNodeFactory.instance.objectNode());
    }
    @Test void routesMapEvents() {
        var template = mock(SimpMessagingTemplate.class);
        var listener = new DomainEventListener(new ProcessedEvents(20), new EventRouter(template));
        var online = event("1", "professional.online");
        var disconnected = event("2", "professional.disconnected");
        listener.handle(online);
        listener.handle(disconnected);
        verify(template).convertAndSend(EventRouter.MAP_TOPIC, online);
        verify(template).convertAndSend(EventRouter.MAP_TOPIC, disconnected);
    }
    @Test void duplicateEventIsSentOnce() {
        var template = mock(SimpMessagingTemplate.class);
        var listener = new DomainEventListener(new ProcessedEvents(20), new EventRouter(template));
        var online = event("1", "professional.online");
        listener.handle(online);
        listener.handle(online);
        verify(template, times(1)).convertAndSend(EventRouter.MAP_TOPIC, online);
    }
    @Test void ignoresUnknownType() {
        var template = mock(SimpMessagingTemplate.class);
        new DomainEventListener(new ProcessedEvents(20), new EventRouter(template)).handle(event("1", "other.event"));
        verifyNoInteractions(template);
    }
    @Test void rejectsEventsWithoutId() {
        var listener = new DomainEventListener(new ProcessedEvents(20), new EventRouter(mock(SimpMessagingTemplate.class)));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.handle(new EventEnvelope(null, "professional.online", 1, Instant.now(), null, null)));
    }
}
