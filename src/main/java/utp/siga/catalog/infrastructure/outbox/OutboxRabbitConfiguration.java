package utp.siga.catalog.infrastructure.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OutboxRabbitConfiguration {
    @Bean TopicExchange catalogEventsExchange(@Value("${catalog.outbox.publisher.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
