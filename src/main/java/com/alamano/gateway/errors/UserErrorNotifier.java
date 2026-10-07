package com.alamano.gateway.errors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Lazy
public class UserErrorNotifier {
    private static final Logger log = LoggerFactory.getLogger(UserErrorNotifier.class);
    // Los rechazos de seguridad van en INFO para poder auditarlos; los demás avisos (no_active_service,
    // service_unknown...) pueden repetirse cada 2 s y van en DEBUG.
    private static final Set<String> SECURITY_REJECTIONS = Set.of("subscription_denied", "forbidden");

    private final SimpMessagingTemplate messagingTemplate;

    public UserErrorNotifier(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void notify(String userName, String error, String message, String destination) {
        if (SECURITY_REJECTIONS.contains(error)) {
            log.info("Rechazo de seguridad {}: usuario {}, destino {}.", error, userName, destination);
        } else {
            log.debug("Aviso {}: usuario {}, destino {}.", error, userName, destination);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.put("message", message);
        payload.put("destination", destination);
        payload.put("occurredAt", Instant.now());
        messagingTemplate.convertAndSendToUser(userName, "/queue/errors", payload);
    }
}
