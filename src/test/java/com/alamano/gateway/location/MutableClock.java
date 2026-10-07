package com.alamano.gateway.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// Reloj de prueba que solo avanza cuando la prueba lo pide.
class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) { this.now = start; }

    void advance(Duration duration) { now = now.plus(duration); }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
