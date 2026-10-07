package com.alamano.gateway.errors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Lazy
public class UserErrorNotifier {
    private final SimpMessagingTemplate messagingTemplate;

    public UserErrorNotifier(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void notify(String userName, String error, String message, String destination) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.put("message", message);
        payload.put("destination", destination);
        payload.put("occurredAt", Instant.now());
        messagingTemplate.convertAndSendToUser(userName, "/queue/errors", payload);
    }
}
