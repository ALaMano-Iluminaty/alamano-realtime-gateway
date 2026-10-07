package com.alamano.gateway.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class UserErrorNotifierTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(UserErrorNotifier.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void securityRejectionsAreLoggedAtInfoWithUserAndDestination() {
        UserErrorNotifier notifier = new UserErrorNotifier(mock(SimpMessagingTemplate.class));

        notifier.notify("client-b", "subscription_denied", "No es tuyo", "/topic/service.s-1");
        notifier.notify("client-b", "forbidden", "No autorizado", "/topic/service.s-1");

        assertEquals(2, appender.list.size());
        appender.list.forEach(event -> {
            assertEquals(Level.INFO, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("client-b"), event::getFormattedMessage);
            assertTrue(event.getFormattedMessage().contains("/topic/service.s-1"), event::getFormattedMessage);
        });
        assertTrue(appender.list.get(0).getFormattedMessage().contains("subscription_denied"));
        assertTrue(appender.list.get(1).getFormattedMessage().contains("forbidden"));
    }

    @Test
    void repeatableNoticesAreLoggedAtDebug() {
        UserErrorNotifier notifier = new UserErrorNotifier(mock(SimpMessagingTemplate.class));

        notifier.notify("pro-1", "no_active_service", "Sin servicio", "/app/location");
        notifier.notify("client-1", "service_unknown", "No existe", "/topic/service.s-2");

        assertEquals(2, appender.list.size());
        appender.list.forEach(event -> assertEquals(Level.DEBUG, event.getLevel()));
    }

    @Test
    void sendsErrorPayloadToUserQueue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserErrorNotifier notifier = new UserErrorNotifier(template);

        notifier.notify("client-1", "forbidden", "No autorizado", "/topic/private");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(template).convertAndSendToUser(eq("client-1"), eq("/queue/errors"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("forbidden", payload.get("error"));
        assertEquals("No autorizado", payload.get("message"));
        assertEquals("/topic/private", payload.get("destination"));
        assertNotNull(payload.get("occurredAt"));
        assertNotNull(Instant.parse(payload.get("occurredAt").toString()));
    }
}
