package com.alamano.gateway.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record EventEnvelope(String eventId, String type, int schemaVersion, Instant occurredAt,
        String correlationId, JsonNode payload) { }
