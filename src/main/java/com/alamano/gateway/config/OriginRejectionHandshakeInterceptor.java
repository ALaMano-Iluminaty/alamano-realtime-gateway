package com.alamano.gateway.config;

import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class OriginRejectionHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginRejectionHandshakeInterceptor.class);

    private final String[] allowedOrigins;

    public OriginRejectionHandshakeInterceptor(
            @Value("${alamano.gateway.allowed-origins}") String allowedOriginsConfig) {
        this.allowedOrigins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .toArray(String[]::new);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null) return true;
        for (String allowed : allowedOrigins) {
            if (allowed.equals("*") || allowed.equalsIgnoreCase(origin)) return true;
        }
        log.warn("origin_rejected origin={}", origin);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {}
}
