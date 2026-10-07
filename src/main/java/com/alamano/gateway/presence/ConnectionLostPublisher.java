package com.alamano.gateway.presence;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConnectionLostPublisher {
    public static final String EVENT_TYPE = "professional.connection.lost";
    public static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(ConnectionLostPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String gatewayInstance;

    public ConnectionLostPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
            @Value("${alamano.gateway.instance-id}") String gatewayInstance) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.gatewayInstance = gatewayInstance;
    }

    public void publish(String professionalId, Instant lostAt) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("professionalId", professionalId);
        payload.put("gatewayInstance", gatewayInstance);
        EventEnvelope envelope = new EventEnvelope(
                eventId, EVENT_TYPE, SCHEMA_VERSION, lostAt, correlationId, payload);

        try {
            MDC.put("correlationId", correlationId);
            MDC.put("eventId", eventId);
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, EVENT_TYPE, envelope);
            log.info("Evento publicado: {}", EVENT_TYPE);
        } catch (AmqpException exception) {
            log.error("No se pudo publicar la pérdida de conexión del vendedor {}.", professionalId, exception);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
    }
}
