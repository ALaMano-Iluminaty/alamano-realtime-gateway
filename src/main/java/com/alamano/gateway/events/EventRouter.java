package com.alamano.gateway.events;

import com.alamano.gateway.services.ActiveServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventRouter {
    public static final String MAP_TOPIC = "/topic/map";
    public static final String SERVICE_TOPIC_PREFIX = "/topic/service.";
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveServiceRegistry activeServices;
    public EventRouter(SimpMessagingTemplate messagingTemplate, ActiveServiceRegistry activeServices) {
        this.messagingTemplate = messagingTemplate;
        this.activeServices = activeServices;
    }
    public void route(EventEnvelope event) {
        switch (event.type()) {
            case "professional.online", "professional.disconnected" -> messagingTemplate.convertAndSend(MAP_TOPIC, event);
            case "service.status.changed" -> applyServiceStatus(event);
            case "tracking.updated" -> routeTracking(event);
            default -> log.debug("Evento ignorado por el enrutador: {}", event.type());
        }
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

    // Solo alimenta el registro de servicios activos; reenviarlo a /topic/service.{id} es otra tarea (AB#303).
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
        activeServices.apply(serviceId, professionalId, clientId, status, version.asLong());
    }

    static String text(JsonNode payload, String field) {
        if (payload == null) return null;
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
