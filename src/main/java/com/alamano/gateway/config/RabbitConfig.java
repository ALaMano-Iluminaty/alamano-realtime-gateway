package com.alamano.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EVENTS_EXCHANGE = "alamano.events";
    public static final String DLX_EXCHANGE = "alamano.events.dlx";

    // Exclusiva además de no durable: RabbitMQ 4 rechaza por defecto las colas no durables compartibles
    // (transient_nonexcl_queues). Muere con la conexión de esta instancia, que es lo que se busca.
    @Bean
    org.springframework.amqp.core.Queue gatewayQueue(@Value("${alamano.gateway.instance-id}") String instanceId) {
        return QueueBuilder.nonDurable("gateway." + instanceId).exclusive().autoDelete()
                .deadLetterExchange(DLX_EXCHANGE).deadLetterRoutingKey("gateway.dlq").build();
    }

    @Bean
    Declarables gatewayBindings(org.springframework.amqp.core.Queue gatewayQueue) {
        var exchange = ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
        var dlx = ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
        return new Declarables(exchange, dlx,
                BindingBuilder.bind(gatewayQueue).to(exchange).with("professional.*").noargs(),
                BindingBuilder.bind(gatewayQueue).to(exchange).with("service.*").noargs(),
                BindingBuilder.bind(gatewayQueue).to(exchange).with("tracking.*").noargs());
    }

    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
