package com.alamano.gateway.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProcessedEvents {
    private final Map<String, Boolean> events;
    public ProcessedEvents(@Value("${alamano.gateway.dedup-capacity:10000}") int capacity) {
        int safeCapacity = Math.max(1, capacity);
        this.events = Collections.synchronizedMap(new LinkedHashMap<>(safeCapacity, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > safeCapacity;
            }
        });
    }
    public boolean markIfNew(String eventId) {
        synchronized (events) {
            if (events.containsKey(eventId)) return false;
            events.put(eventId, Boolean.TRUE);
            return true;
        }
    }
}
