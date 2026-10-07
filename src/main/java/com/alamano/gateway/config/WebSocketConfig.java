package com.alamano.gateway.config;

import com.alamano.gateway.security.JwtChannelInterceptor;
import com.alamano.gateway.security.DestinationAuthorizationInterceptor;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtChannelInterceptor interceptor;
    private final DestinationAuthorizationInterceptor destinationAuthorizationInterceptor;
    private final String[] allowedOrigins;
    private final TaskScheduler heartbeatScheduler;

    // El scheduler del broker se inyecta con @Lazy para evitar una dependencia circular:
    // ese bean lo crea la misma configuración de WebSocket que usa esta clase.
    public WebSocketConfig(JwtChannelInterceptor interceptor,
            DestinationAuthorizationInterceptor destinationAuthorizationInterceptor,
            @Value("${alamano.gateway.allowed-origins}") String allowedOrigins,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
        this.interceptor = interceptor;
        this.destinationAuthorizationInterceptor = destinationAuthorizationInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).toArray(String[]::new);
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000}).setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor, destinationAuthorizationInterceptor);
    }
}
