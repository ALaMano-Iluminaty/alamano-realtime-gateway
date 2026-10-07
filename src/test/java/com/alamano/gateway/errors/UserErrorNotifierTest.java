package com.alamano.gateway.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class UserErrorNotifierTest {
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
