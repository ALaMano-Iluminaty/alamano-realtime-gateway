package com.alamano.gateway.location;

import com.alamano.gateway.errors.UserErrorNotifier;
import com.alamano.gateway.services.ActiveService;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.security.Principal;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Controller;

// Recibe SEND /app/location del vendedor y publica location.updated para el servicio que tiene en curso.
@Controller
public class LocationController {
    private static final Logger log = LoggerFactory.getLogger(LocationController.class);
    private static final String PROFESSIONAL_ROLE = "ROLE_PROFESSIONAL";

    private final ActiveServiceRegistry activeServices;
    private final LocationRateLimiter rateLimiter;
    private final LocationPublisher publisher;
    private final UserErrorNotifier errorNotifier;
    private final Clock clock;

    public LocationController(ActiveServiceRegistry activeServices, LocationRateLimiter rateLimiter,
            LocationPublisher publisher, @Lazy UserErrorNotifier errorNotifier, Clock clock) {
        this.activeServices = activeServices;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.errorNotifier = errorNotifier;
        this.clock = clock;
    }

    @MessageMapping("/location")
    public void receiveLocation(LocationMessage message, Principal principal) {
        if (!isProfessional(principal)) {
            notify(principal, "forbidden", "Solo un vendedor puede compartir su ubicación");
            return;
        }
        // El id del vendedor sale siempre del token validado, nunca del mensaje.
        String professionalId = principal.getName();
        if (!validCoordinates(message)) {
            log.warn("Ubicación ignorada del vendedor {}: coordenadas nulas o fuera de rango.", professionalId);
            notify(principal, "invalid_location", "La ubicación no es válida");
            return;
        }
        Optional<ActiveService> service = activeServices.findByProfessional(professionalId);
        if (service.isEmpty()) {
            log.debug("Ubicación ignorada del vendedor {}: no tiene un servicio en curso.", professionalId);
            notify(principal, "no_active_service", "Solo puedes compartir tu ubicación durante un servicio en curso");
            return;
        }
        if (!rateLimiter.tryAcquire(professionalId)) {
            log.debug("Ubicación ignorada del vendedor {}: llegó antes del intervalo mínimo.", professionalId);
            return;
        }
        publisher.publish(professionalId, service.get().serviceId(), message.latitude(), message.longitude(),
                clock.instant());
    }

    private void notify(Principal principal, String error, String message) {
        if (principal != null) errorNotifier.notify(principal.getName(), error, message, "/app/location");
    }

    private static boolean isProfessional(Principal principal) {
        return principal instanceof JwtAuthenticationToken authentication
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(PROFESSIONAL_ROLE::equals);
    }

    private static boolean validCoordinates(LocationMessage message) {
        if (message == null || message.latitude() == null || message.longitude() == null) return false;
        double latitude = message.latitude();
        double longitude = message.longitude();
        return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
}
