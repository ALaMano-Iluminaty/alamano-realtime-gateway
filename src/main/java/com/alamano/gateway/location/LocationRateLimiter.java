package com.alamano.gateway.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Acepta como máximo una ubicación por vendedor cada alamano.gateway.location-min-interval-ms.
@Component
public class LocationRateLimiter {
    private final Clock clock;
    private final Duration minInterval;
    private final ConcurrentHashMap<String, Instant> lastAccepted = new ConcurrentHashMap<>();

    public LocationRateLimiter(Clock clock,
            @Value("${alamano.gateway.location-min-interval-ms:2000}") long minIntervalMs) {
        this.clock = clock;
        this.minInterval = Duration.ofMillis(minIntervalMs);
    }

    public boolean tryAcquire(String professionalId) {
        Instant now = clock.instant();
        AtomicBoolean accepted = new AtomicBoolean(false);
        // compute es atómico por vendedor: dos ubicaciones simultáneas no pueden aceptarse ambas.
        lastAccepted.compute(professionalId, (id, last) -> {
            if (last == null || Duration.between(last, now).compareTo(minInterval) >= 0) {
                accepted.set(true);
                return now;
            }
            return last;
        });
        return accepted.get();
    }
}
