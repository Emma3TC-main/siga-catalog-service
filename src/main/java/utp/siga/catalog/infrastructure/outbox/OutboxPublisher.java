package utp.siga.catalog.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxPublisher {
    private final TransactionTemplate transaction;
    private final PostgresOutboxEventStore events;
    private final ProductCreatedEnvelopeFactory envelopes;
    private final RabbitTemplate rabbit;
    private final String exchange, routingKey;
    private final long confirmTimeout, retryBase, retryMax;

    public OutboxPublisher(TransactionTemplate transaction, PostgresOutboxEventStore events, ProductCreatedEnvelopeFactory envelopes,
            RabbitTemplate rabbit, @Value("${catalog.outbox.publisher.exchange}") String exchange,
            @Value("${catalog.outbox.publisher.routing-key}") String routingKey,
            @Value("${catalog.outbox.publisher.confirm-timeout-ms}") long confirmTimeout,
            @Value("${catalog.outbox.publisher.retry-base-ms}") long retryBase,
            @Value("${catalog.outbox.publisher.retry-max-ms}") long retryMax) {
        this.transaction=transaction; this.events=events; this.envelopes=envelopes; this.rabbit=rabbit; this.exchange=exchange; this.routingKey=routingKey;
        this.confirmTimeout=confirmTimeout; this.retryBase=retryBase; this.retryMax=retryMax;
    }

    public boolean publishNext() {
        Boolean result = transaction.execute(status -> {
            Optional<OutboxEvent> candidate = events.claimDueProductCreated();
            if (candidate.isEmpty()) return false;
            OutboxEvent event = candidate.get();
            try {
                String body = envelopes.create(event);
                CorrelationData correlation = new CorrelationData(event.id().toString());
                Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json").setMessageId(event.id().toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                rabbit.send(exchange, routingKey, message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS);
                if (confirm == null || !confirm.isAck()) throw new IllegalStateException("RabbitMQ did not confirm the event");
                events.markPublished(event);
                return true;
            } catch (Exception ex) {
                events.scheduleRetry(event, retryDelay(event));
                return false;
            }
        });
        return Boolean.TRUE.equals(result);
    }

    private long retryDelay(OutboxEvent event) {
        long factor = 1L << Math.min(event.attempts() + 1, 20);
        return Math.min(retryMax, retryBase > retryMax / factor ? retryMax : retryBase * factor);
    }
}
