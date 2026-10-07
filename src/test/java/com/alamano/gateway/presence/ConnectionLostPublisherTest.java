package com.alamano.gateway.presence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class ConnectionLostPublisherTest {
    @Test
    void publishesConnectionLostEnvelope() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        var publisher = new ConnectionLostPublisher(rabbitTemplate, new ObjectMapper(), "gateway-1");
        Instant lostAt = Instant.parse("2026-10-01T12:00:00Z");

        publisher.publish("pro-1", lostAt);

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate).convertAndSend(eq("alamano.events"), eq(ConnectionLostPublisher.EVENT_TYPE), captor.capture());
        EventEnvelope event = captor.getValue();
        assertNotNull(event.eventId());
        assertNotNull(event.correlationId());
        assertEquals(ConnectionLostPublisher.EVENT_TYPE, event.type());
        assertEquals(1, event.schemaVersion());
        assertEquals(lostAt, event.occurredAt());
        assertEquals("pro-1", event.payload().get("professionalId").asText());
        assertEquals("gateway-1", event.payload().get("gatewayInstance").asText());
    }

    @Test
    void amqpFailureDoesNotEscape() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new AmqpException("RabbitMQ no disponible")).when(rabbitTemplate)
                .convertAndSend(eq("alamano.events"), eq(ConnectionLostPublisher.EVENT_TYPE),
                        org.mockito.ArgumentMatchers.any(EventEnvelope.class));
        var publisher = new ConnectionLostPublisher(rabbitTemplate, new ObjectMapper(), "gateway-1");
        assertDoesNotThrow(() -> publisher.publish("pro-1", Instant.now()));
    }
}
