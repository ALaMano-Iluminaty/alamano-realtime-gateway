package com.alamano.gateway.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DomainEventListener {
    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);
    private final ProcessedEvents processedEvents;
    private final EventRouter eventRouter;
    public DomainEventListener(ProcessedEvents processedEvents, EventRouter eventRouter) {
        this.processedEvents = processedEvents;
        this.eventRouter = eventRouter;
    }
    @RabbitListener(queues = "#{gatewayQueue.name}")
    public void handle(EventEnvelope event) {
        if (event == null || event.eventId() == null || event.type() == null) {
            throw new AmqpRejectAndDontRequeueException("El evento debe incluir eventId y type.");
        }
        try {
            if (event.correlationId() != null) MDC.put("correlationId", event.correlationId());
            MDC.put("eventId", event.eventId());
            if (processedEvents.markIfNew(event.eventId())) {
                log.info("Evento recibido: {}", event.type());
                eventRouter.route(event);
            } else {
                log.debug("Evento duplicado descartado: {}", event.type());
            }
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
    }
}
