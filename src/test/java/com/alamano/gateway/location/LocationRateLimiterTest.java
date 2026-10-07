package com.alamano.gateway.location;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LocationRateLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T12:00:00Z"));
    private final LocationRateLimiter limiter = new LocationRateLimiter(clock, 2000);

    @Test
    void acceptsOnlyOncePerInterval() {
        assertTrue(limiter.tryAcquire("pro-1"));
        clock.advance(Duration.ofMillis(500));
        assertFalse(limiter.tryAcquire("pro-1"));
        clock.advance(Duration.ofMillis(1500));
        assertTrue(limiter.tryAcquire("pro-1"));
    }

    @Test
    void professionalsDoNotAffectEachOther() {
        assertTrue(limiter.tryAcquire("pro-1"));
        assertTrue(limiter.tryAcquire("pro-2"));
        clock.advance(Duration.ofMillis(500));
        assertFalse(limiter.tryAcquire("pro-1"));
        assertFalse(limiter.tryAcquire("pro-2"));
    }
}
