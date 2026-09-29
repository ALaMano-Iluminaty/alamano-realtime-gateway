package com.alamano.gateway.events;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProcessedEventsTest {
    @Test void duplicateIdIsRejected() {
        var events = new ProcessedEvents(10);
        assertTrue(events.markIfNew("one"));
        assertFalse(events.markIfNew("one"));
    }
    @Test void oldestIdIsForgottenWhenCapacityIsExceeded() {
        var events = new ProcessedEvents(2);
        events.markIfNew("one"); events.markIfNew("two"); events.markIfNew("three");
        assertTrue(events.markIfNew("one"));
    }
}
