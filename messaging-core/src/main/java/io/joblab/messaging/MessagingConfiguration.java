package io.joblab.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {

    public static final String EXCHANGE = "joblab.dispatch";
    public static final String QUEUE = "joblab.work";
    public static final String ROUTING_KEY = "report";
    private static final String DEAD_EXCHANGE = "joblab.dead";

    @Bean
    Declarables jobTopology() {
        var dispatch = new DirectExchange(EXCHANGE);
        var dead = new DirectExchange(DEAD_EXCHANGE);
        var work = QueueBuilder.durable(QUEUE).quorum()
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(ROUTING_KEY).build();
        var deadWork = QueueBuilder.durable(QUEUE + ".dlq").quorum().build();
        return new Declarables(dispatch, dead, work, deadWork,
                BindingBuilder.bind(work).to(dispatch).with(ROUTING_KEY),
                BindingBuilder.bind(deadWork).to(dead).with(ROUTING_KEY));
    }

    @Bean
    EventJson eventJson(ObjectMapper mapper) {
        return new EventJson(mapper);
    }
}
