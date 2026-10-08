package com.alamano.gateway.location;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alamano.gateway.errors.UserErrorNotifier;
import com.alamano.gateway.services.ActiveServiceRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class LocationControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final ActiveServiceRegistry registry = new ActiveServiceRegistry();
    private final LocationPublisher publisher = mock(LocationPublisher.class);
    private final UserErrorNotifier errorNotifier = mock(UserErrorNotifier.class);
    private final LocationController controller = new LocationController(registry,
            new LocationRateLimiter(clock, 2000), publisher, errorNotifier, clock);

    @BeforeEach
    void activeService() {
        registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 1);
    }

    @Test
    void professionalWithActiveServicePublishesOnceWithoutWarning() {
        controller.receiveLocation(new LocationMessage(4.7826, -74.0435), user("pro-1", "PROFESSIONAL"));
        verify(publisher).publish("pro-1", "s-1", 4.7826, -74.0435, NOW);
        verifyNoInteractions(errorNotifier);
    }

    @Test
    void clientIsWarnedAndDoesNotPublish() {
        controller.receiveLocation(new LocationMessage(4.7826, -74.0435), user("client-1", "CLIENT"));
        verify(errorNotifier).notify("client-1", "forbidden",
                "Solo un vendedor puede compartir su ubicación", "/app/location");
        verifyNoInteractions(publisher);
    }

    @Test
    void professionalWithoutActiveServiceIsWarnedAndDoesNotPublish() {
        controller.receiveLocation(new LocationMessage(4.7826, -74.0435), user("pro-2", "PROFESSIONAL"));
        verify(errorNotifier).notify("pro-2", "no_active_service",
                "Solo puedes compartir tu ubicación durante un servicio en curso", "/app/location");
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null, -74.0", "4.7, null", "90.1, -74.0", "-90.1, -74.0",
            "4.7, 180.1", "4.7, -180.1", "NaN, -74.0"})
    void invalidCoordinatesAreWarnedAndDoNotPublish(Double latitude, Double longitude) {
        controller.receiveLocation(new LocationMessage(latitude, longitude), user("pro-1", "PROFESSIONAL"));
        verify(errorNotifier).notify("pro-1", "invalid_location", "La ubicación no es válida", "/app/location");
        verifyNoInteractions(publisher);
    }

    @Test
    void secondLocationWithinIntervalIsDroppedWithoutWarning() {
        var professional = user("pro-1", "PROFESSIONAL");
        controller.receiveLocation(new LocationMessage(4.7826, -74.0435), professional);
        controller.receiveLocation(new LocationMessage(4.7830, -74.0440), professional);
        verify(publisher, times(1)).publish(eq("pro-1"), eq("s-1"), anyDouble(), anyDouble(), eq(NOW));
        verifyNoInteractions(errorNotifier);
    }

    private static JwtAuthenticationToken user(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject)
                .issuedAt(NOW.minusSeconds(1)).expiresAt(NOW.plusSeconds(60)).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), subject);
    }
}
