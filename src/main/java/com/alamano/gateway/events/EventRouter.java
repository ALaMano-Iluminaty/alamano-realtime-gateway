package com.alamano.gateway.events;

import com.alamano.gateway.services.ActiveServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventRouter {
    public static final String MAP_TOPIC = "/topic/map";
    public static final String SERVICE_TOPIC_PREFIX = "/topic/service.";
    public static final String PROFESSIONAL_SERVICES_QUEUE = "/queue/services";
    /** HU-Q6/Q10: tiempo entre que el evento ocurrio en el Core y el Gateway lo reparte. */
    public static final String EVENT_LATENCY_METRIC = "alamano.event.latency";
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveServiceRegistry activeServices;
    private final MeterRegistry meterRegistry;
    public EventRouter(SimpMessagingTemplate messagingTemplate, ActiveServiceRegistry activeServices,
            MeterRegistry meterRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.activeServices = activeServices;
        this.meterRegistry = meterRegistry;
    }
    public void route(EventEnvelope event) {
        recordLatency(event);
        switch (event.type()) {
            case "professional.online", "professional.disconnected" -> messagingTemplate.convertAndSend(MAP_TOPIC, event);
            case "service.status.changed" -> applyServiceStatus(event);
            case "tracking.updated" -> routeTracking(event);
            default -> log.debug("Evento ignorado por el enrutador: {}", event.type());
        }
    }

    // Si los relojes van desfasados la resta puede dar negativa: se registra como cero.
    private void recordLatency(EventEnvelope event) {
        if (event.occurredAt() == null) return;
        Duration latency = Duration.between(event.occurredAt(), Instant.now());
        Timer.builder(EVENT_LATENCY_METRIC)
                .tag("type", event.type())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(latency.isNegative() ? Duration.ZERO : latency);
    }

    // El cliente del servicio recibe el sobre completo (ubicación y ETA) en /topic/service.{serviceId}.
    private void routeTracking(EventEnvelope event) {
        String serviceId = text(event.payload(), "serviceId");
        if (serviceId == null) {
            log.warn("Evento tracking.updated sin serviceId: se ignora.");
            return;
        }
        messagingTemplate.convertAndSend(SERVICE_TOPIC_PREFIX + serviceId, event);
    }

    // Solo los cambios nuevos se envían al canal dedicado del servicio.
    private void applyServiceStatus(EventEnvelope event) {
        JsonNode payload = event.payload();
        String serviceId = text(payload, "serviceId");
        String professionalId = text(payload, "professionalId");
        String clientId = text(payload, "clientId");
        String status = text(payload, "status");
        JsonNode version = payload == null ? null : payload.get("version");
        if (serviceId == null || professionalId == null || clientId == null || status == null
                || version == null || !version.canConvertToLong()) {
            log.warn("Evento service.status.changed incompleto: se ignora.");
            return;
        }
        boolean applied = activeServices.apply(serviceId, professionalId, clientId, status, version.asLong());
        if (!applied) return;
        messagingTemplate.convertAndSend(SERVICE_TOPIC_PREFIX + serviceId, event);
        // El vendedor aún no conoce el serviceId: el aviso de reserva le llega por su cola de usuario.
        if ("RESERVED".equals(status)) {
            messagingTemplate.convertAndSendToUser(professionalId, PROFESSIONAL_SERVICES_QUEUE, event);
        }
    }

    static String text(JsonNode payload, String field) {
        if (payload == null) return null;
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
