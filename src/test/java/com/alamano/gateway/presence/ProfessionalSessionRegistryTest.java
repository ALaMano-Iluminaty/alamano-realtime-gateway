package com.alamano.gateway.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ProfessionalSessionRegistryTest {
    @Test
    void unregisterReturnsTrueForSingleSession() {
        var registry = new ProfessionalSessionRegistry();
        registry.register("pro-1", "session-1");
        assertTrue(registry.unregister("pro-1", "session-1"));
        assertEquals(0, registry.sessionCount("pro-1"));
    }

    @Test
    void onlyLastOfTwoSessionsReturnsTrue() {
        var registry = new ProfessionalSessionRegistry();
        registry.register("pro-1", "session-1");
        registry.register("pro-1", "session-2");
        assertFalse(registry.unregister("pro-1", "session-1"));
        assertTrue(registry.unregister("pro-1", "session-2"));
    }

    @Test
    void repeatedUnregisterIsIdempotent() {
        var registry = new ProfessionalSessionRegistry();
        registry.register("pro-1", "session-1");
        assertTrue(registry.unregister("pro-1", "session-1"));
        assertFalse(registry.unregister("pro-1", "session-1"));
    }

    @Test
    void unknownSessionDoesNotSignalLastDisconnect() {
        var registry = new ProfessionalSessionRegistry();
        registry.register("pro-1", "session-1");
        assertFalse(registry.unregister("pro-1", "unknown"));
    }

    @Test
    void concurrentUnregistersSignalExactlyOneLastSession() throws Exception {
        var registry = new ProfessionalSessionRegistry();
        int count = 50;
        for (int i = 0; i < count; i++) registry.register("pro-1", "session-" + i);

        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String sessionId = "session-" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.unregister("pro-1", sessionId);
                }));
            }
            ready.await();
            start.countDown();
            long lastSessions = 0;
            for (Future<Boolean> result : results) if (result.get()) lastSessions++;
            assertEquals(1, lastSessions);
            assertEquals(0, registry.sessionCount("pro-1"));
        } finally {
            executor.shutdownNow();
        }
    }
}
