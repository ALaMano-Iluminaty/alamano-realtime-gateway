package com.alamano.gateway.events;

import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DomainEventListener {
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
            if (processedEvents.markIfNew(event.eventId())) eventRouter.route(event);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
    }
}
