package com.alamano.gateway.security;

import com.alamano.gateway.errors.UserErrorNotifier;
import com.alamano.gateway.services.ActiveService;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class DestinationAuthorizationInterceptor implements ChannelInterceptor {
    private static final String SERVICE_TOPIC_PREFIX = "/topic/service.";
    private static final String MAP_TOPIC = "/topic/map";
    private final ActiveServiceRegistry activeServices;
    private final UserErrorNotifier errorNotifier;

    public DestinationAuthorizationInterceptor(ActiveServiceRegistry activeServices,
            @Lazy UserErrorNotifier errorNotifier) {
        this.activeServices = activeServices;
        this.errorNotifier = errorNotifier;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            return authorizeSubscription(message, accessor.getUser(), accessor.getDestination());
        }
        if (accessor.getCommand() == StompCommand.SEND) {
            return authorizeSend(message, accessor.getUser(), accessor.getDestination());
        }
        return message;
    }

    private Message<?> authorizeSubscription(Message<?> message, Principal principal, String destination) {
        String userName = authenticatedUser(principal);
        if (userName == null) {
            notifyIfPossible(principal, "subscription_denied", "Debes iniciar sesión para suscribirte", destination);
            return null;
        }
        if (MAP_TOPIC.equals(destination) || (destination != null && destination.startsWith("/user/"))) {
            return message;
        }
        if (destination != null && destination.startsWith(SERVICE_TOPIC_PREFIX)) {
            String serviceId = destination.substring(SERVICE_TOPIC_PREFIX.length());
            ActiveService service = activeServices.findByService(serviceId).orElse(null);
            if (service == null) {
                errorNotifier.notify(userName, "service_unknown",
                        "El servicio no existe o todavía no está disponible; vuelve a intentarlo en unos segundos",
                        destination);
                return null;
            }
            if (userName.equals(service.professionalId()) || userName.equals(service.clientId())) return message;
            errorNotifier.notify(userName, "subscription_denied", "No puedes seguir un servicio que no es tuyo",
                    destination);
            return null;
        }
        if (destination != null && (destination.startsWith("/topic/") || destination.startsWith("/queue/"))) {
            errorNotifier.notify(userName, "subscription_denied", "No puedes suscribirte a este destino", destination);
            return null;
        }
        return message;
    }

    private Message<?> authorizeSend(Message<?> message, Principal principal, String destination) {
        if (destination != null && destination.startsWith("/app/")) return message;
        notifyIfPossible(principal, "forbidden", "No puedes enviar mensajes directamente a este destino", destination);
        return null;
    }

    private void notifyIfPossible(Principal principal, String error, String message, String destination) {
        String userName = principal == null ? null : principal.getName();
        if (userName != null) errorNotifier.notify(userName, error, message, destination);
    }

    private static String authenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return null;
    }
}
