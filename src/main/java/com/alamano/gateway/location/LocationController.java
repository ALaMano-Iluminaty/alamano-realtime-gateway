package com.alamano.gateway.location;

import com.alamano.gateway.services.ActiveService;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.security.Principal;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
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
    private final Clock clock;

    public LocationController(ActiveServiceRegistry activeServices, LocationRateLimiter rateLimiter,
            LocationPublisher publisher, Clock clock) {
        this.activeServices = activeServices;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.clock = clock;
    }

    @MessageMapping("/location")
    public void receiveLocation(LocationMessage message, Principal principal) {
        if (!isProfessional(principal)) {
            log.warn("Ubicación ignorada: el usuario no es un vendedor.");
            return;
        }
        // El id del vendedor sale siempre del token validado, nunca del mensaje.
        String professionalId = principal.getName();
        if (!validCoordinates(message)) {
            log.warn("Ubicación ignorada del vendedor {}: coordenadas nulas o fuera de rango.", professionalId);
            return;
        }
        Optional<ActiveService> service = activeServices.findByProfessional(professionalId);
        if (service.isEmpty()) {
            log.debug("Ubicación ignorada del vendedor {}: no tiene un servicio en curso.", professionalId);
            return;
        }
        if (!rateLimiter.tryAcquire(professionalId)) {
            log.debug("Ubicación ignorada del vendedor {}: llegó antes del intervalo mínimo.", professionalId);
            return;
        }
        publisher.publish(professionalId, service.get().serviceId(), message.latitude(), message.longitude(),
                clock.instant());
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
