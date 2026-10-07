package com.alamano.gateway.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActiveServiceRegistryTest {
    @Test
    void reservedRegistersServiceAndProfessional() {
        var registry = new ActiveServiceRegistry();
        registry.apply("s-1", "pro-1", "client-1", "RESERVED", 1);
        var expected = new ActiveService("s-1", "pro-1", "client-1", "RESERVED", 1);
        assertEquals(expected, registry.findByService("s-1").orElseThrow());
        assertEquals(expected, registry.findByProfessional("pro-1").orElseThrow());
    }

    @Test
    void higherVersionUpdatesStatus() {
        var registry = new ActiveServiceRegistry();
        registry.apply("s-1", "pro-1", "client-1", "RESERVED", 1);
        registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 2);
        assertEquals("EN_ROUTE", registry.findByProfessional("pro-1").orElseThrow().status());
        assertEquals(2, registry.findByService("s-1").orElseThrow().version());
    }

    @Test
    void lowerOrEqualVersionIsIgnored() {
        var registry = new ActiveServiceRegistry();
        registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 2);
        registry.apply("s-1", "pro-1", "client-1", "ARRIVED", 2);
        registry.apply("s-1", "pro-1", "client-1", "RESERVED", 1);
        var service = registry.findByService("s-1").orElseThrow();
        assertEquals("EN_ROUTE", service.status());
        assertEquals(2, service.version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED"})
    void terminalStatusRemovesServiceAndProfessional(String terminal) {
        var registry = new ActiveServiceRegistry();
        registry.apply("s-1", "pro-1", "client-1", "IN_PROGRESS", 3);
        registry.apply("s-1", "pro-1", "client-1", terminal, 4);
        assertTrue(registry.findByService("s-1").isEmpty());
        assertTrue(registry.findByProfessional("pro-1").isEmpty());
    }

    @Test
    void oldEventAfterTerminalDoesNotRegisterAgain() {
        var registry = new ActiveServiceRegistry();
        registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", 2);
        registry.apply("s-1", "pro-1", "client-1", "COMPLETED", 5);
        registry.apply("s-1", "pro-1", "client-1", "IN_PROGRESS", 4);
        assertTrue(registry.findByService("s-1").isEmpty());
        assertTrue(registry.findByProfessional("pro-1").isEmpty());
    }

    @Test
    void concurrentOutOfOrderVersionsKeepTheHighest() throws Exception {
        var registry = new ActiveServiceRegistry();
        List<Integer> versions = new ArrayList<>(IntStream.rangeClosed(1, 50).boxed().toList());
        Collections.shuffle(versions);

        ExecutorService executor = Executors.newFixedThreadPool(versions.size());
        CountDownLatch ready = new CountDownLatch(versions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int version : versions) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    registry.apply("s-1", "pro-1", "client-1", "EN_ROUTE", version);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> result : results) result.get();
            assertEquals(50, registry.findByService("s-1").orElseThrow().version());
            assertEquals("s-1", registry.findByProfessional("pro-1").orElseThrow().serviceId());
        } finally {
            executor.shutdownNow();
        }
    }
}
