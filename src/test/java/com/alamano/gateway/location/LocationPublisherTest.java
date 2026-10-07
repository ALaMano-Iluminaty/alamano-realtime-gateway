package com.alamano.gateway.location;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.alamano.gateway.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class LocationPublisherTest {
    @Test
    void publishesLocationUpdatedEnvelope() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        var publisher = new LocationPublisher(rabbitTemplate, new ObjectMapper());
        Instant recordedAt = Instant.parse("2026-10-07T12:00:00Z");

        publisher.publish("pro-1", "s-1", 4.7826, -74.0435, recordedAt);

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate).convertAndSend(eq("alamano.events"), eq("location.updated"), captor.capture());
        EventEnvelope event = captor.getValue();
        assertNotNull(event.eventId());
        assertNotNull(event.correlationId());
        assertEquals("location.updated", event.type());
        assertEquals(1, event.schemaVersion());
        assertEquals(recordedAt, event.occurredAt());
        assertEquals("pro-1", event.payload().get("professionalId").asText());
        assertEquals("s-1", event.payload().get("serviceId").asText());
        assertEquals(4.7826, event.payload().get("latitude").asDouble());
        assertEquals(-74.0435, event.payload().get("longitude").asDouble());
        assertEquals("2026-10-07T12:00:00Z", event.payload().get("recordedAt").asText());
    }

    @Test
    void amqpFailureDoesNotEscape() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new AmqpException("RabbitMQ no disponible")).when(rabbitTemplate)
                .convertAndSend(eq("alamano.events"), eq("location.updated"), any(EventEnvelope.class));
        var publisher = new LocationPublisher(rabbitTemplate, new ObjectMapper());
        assertDoesNotThrow(() -> publisher.publish("pro-1", "s-1", 4.7826, -74.0435, Instant.now()));
    }
}
