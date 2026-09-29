package com.alamano.gateway.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventRouter {
    public static final String MAP_TOPIC = "/topic/map";
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);
    private final SimpMessagingTemplate messagingTemplate;
    public EventRouter(SimpMessagingTemplate messagingTemplate) { this.messagingTemplate = messagingTemplate; }
    public void route(EventEnvelope event) {
        if ("professional.online".equals(event.type()) || "professional.disconnected".equals(event.type())) {
            messagingTemplate.convertAndSend(MAP_TOPIC, event);
        } else {
            log.debug("Evento ignorado por el enrutador: {}", event.type());
        }
    }
}
