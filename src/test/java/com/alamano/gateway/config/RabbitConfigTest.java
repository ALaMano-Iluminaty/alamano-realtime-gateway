package com.alamano.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.QueueBuilder;

class RabbitConfigTest {
    @Test
    void bindingsIncludeExactServiceStatusChangedKey() {
        var queue = QueueBuilder.nonDurable("gateway.test").build();
        List<Binding> bindings = new RabbitConfig().gatewayBindings(queue).getDeclarablesByType(Binding.class);
        bindings.forEach(binding -> assertEquals(RabbitConfig.EVENTS_EXCHANGE, binding.getExchange()));
        List<String> routingKeys = bindings.stream().map(Binding::getRoutingKey).toList();
        // service.* no cubre service.status.changed (tres palabras): debe existir el binding exacto.
        assertTrue(routingKeys.contains("service.status.changed"), () -> "Bindings: " + routingKeys);
        assertTrue(routingKeys.containsAll(List.of("professional.*", "service.*", "tracking.*")), () -> "Bindings: " + routingKeys);
        assertEquals(4, routingKeys.size(), () -> "Bindings: " + routingKeys);
    }
}
