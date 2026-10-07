package com.alamano.gateway.location;

import com.alamano.gateway.config.RabbitConfig;
import com.alamano.gateway.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocationPublisher {
    public static final String EVENT_TYPE = "location.updated";
    public static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(LocationPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public LocationPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String professionalId, String serviceId, double latitude, double longitude, Instant recordedAt) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("professionalId", professionalId);
        payload.put("serviceId", serviceId);
        payload.put("latitude", latitude);
        payload.put("longitude", longitude);
        payload.put("recordedAt", recordedAt.toString());
        EventEnvelope envelope = new EventEnvelope(
                eventId, EVENT_TYPE, SCHEMA_VERSION, recordedAt, correlationId, payload);

        try {
            MDC.put("correlationId", correlationId);
            MDC.put("eventId", eventId);
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, EVENT_TYPE, envelope);
            // debug y no info: llega una ubicación cada pocos segundos por vendedor.
            log.debug("Evento publicado: {}", EVENT_TYPE);
        } catch (AmqpException exception) {
            log.error("No se pudo publicar la ubicación del vendedor {}.", professionalId, exception);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
    }
}
