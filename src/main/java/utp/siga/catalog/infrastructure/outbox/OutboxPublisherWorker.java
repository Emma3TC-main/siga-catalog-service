package utp.siga.catalog.infrastructure.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="catalog.outbox.publisher.enabled", havingValue="true")
class OutboxPublisherWorker {
    private final OutboxPublisher publisher;
    OutboxPublisherWorker(OutboxPublisher publisher) { this.publisher = publisher; }
    @Scheduled(fixedDelayString = "${catalog.outbox.publisher.fixed-delay-ms}")
    void publishPendingEvent() { publisher.publishNext(); }
}
