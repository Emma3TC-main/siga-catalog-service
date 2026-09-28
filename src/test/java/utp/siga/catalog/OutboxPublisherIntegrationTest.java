package utp.siga.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import utp.siga.catalog.infrastructure.outbox.OutboxPublisher;
import utp.siga.catalog.infrastructure.outbox.PostgresOutboxEventStore;
import utp.siga.catalog.infrastructure.outbox.ProductCreatedEnvelopeFactory;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxPublisherIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired TransactionTemplate transaction;
    @Autowired PostgresOutboxEventStore events;
    @Autowired ProductCreatedEnvelopeFactory envelopes;
    @Autowired ObjectMapper json;
    private String queue;

    @BeforeEach void isolateOutbox() { jdbc.update("DELETE FROM catalog.outbox_event"); }

    @AfterEach void cleanup() {
        if (queue != null) new RabbitAdmin(rabbit).deleteQueue(queue);
        jdbc.update("DELETE FROM catalog.outbox_event");
    }

    @Test void publishesPersistentCanonicalEnvelopeOnlyAfterConfirm() throws Exception {
        queue = queue(); UUID id = pending();
        assertTrue(publisher.publishNext());
        var row = jdbc.queryForMap("SELECT status,published_at,attempts FROM catalog.outbox_event WHERE id=?", id);
        assertEquals("PUBLISHED", row.get("status")); assertNotNull(row.get("published_at")); assertEquals(0, row.get("attempts"));
        var message = rabbit.receive(queue, 5000); assertNotNull(message);
        assertEquals("PERSISTENT", message.getMessageProperties().getReceivedDeliveryMode().name());
        JsonNode envelope = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertEquals(id.toString(), envelope.path("eventId").asText());
        assertEquals("ProductCreated", envelope.path("eventType").asText()); assertEquals(1, envelope.path("schemaVersion").asInt());
        assertEquals("Product", envelope.path("aggregateType").asText()); assertEquals("catalog-service", envelope.path("producer").asText());
        assertTrue(envelope.path("correlationId").isTextual()); assertTrue(envelope.path("payload").path("product").path("minStock").isNumber());
    }

    @Test void claimsOnePendingRowSafelyAcrossConcurrentPublishers() throws Exception {
        queue = queue(); pending();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(publisher::publishNext); var second = workers.submit(publisher::publishNext);
            assertTrue(first.get() || second.get());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event WHERE status='PUBLISHED'", Integer.class));
            assertNotNull(rabbit.receive(queue, 5000)); assertNull(rabbit.receive(queue, 300));
        } finally { workers.shutdownNow(); }
    }

    @Test void unavailableBrokerKeepsPendingAndSchedulesBoundedRetry() {
        UUID id = pending();
        CachingConnectionFactory unavailableConnection = new CachingConnectionFactory("127.0.0.1", 1);
        try {
            OutboxPublisher unavailable = new OutboxPublisher(transaction, events, envelopes, new RabbitTemplate(unavailableConnection),
                    "siga.events", "catalog.product.created.v1", 100, 1000, 1000);
            assertFalse(unavailable.publishNext());
        } finally { unavailableConnection.destroy(); }
        var row = jdbc.queryForMap("SELECT status,attempts,next_attempt_at > CURRENT_TIMESTAMP AS delayed FROM catalog.outbox_event WHERE id=?", id);
        assertEquals("PENDING", row.get("status")); assertEquals(1, row.get("attempts")); assertEquals(true, row.get("delayed"));
    }

    @Test void freshPublisherRecoversDueRetryAfterBrokerReturns() {
        queue = queue(); UUID id = pending();
        jdbc.update("UPDATE catalog.outbox_event SET attempts=1,next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", id);
        assertTrue(publisher.publishNext());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM catalog.outbox_event WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM catalog.outbox_event WHERE id=?", Integer.class, id));
        assertNotNull(rabbit.receive(queue, 5000));
    }

    private String queue() {
        queue = "catalog.publisher.test." + UUID.randomUUID(); RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.declareQueue(new Queue(queue, true, false, false));
        admin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(new org.springframework.amqp.core.TopicExchange("siga.events")).with("catalog.product.created.v1"));
        return queue;
    }
    private UUID pending() {
        UUID id=UUID.randomUUID(), product=UUID.randomUUID(), correlation=UUID.randomUUID();
        String payload = "{\"product\":{\"id\":\""+product+"\",\"sku\":\"PUB-"+id.toString().substring(0,8)+"\",\"productType\":\"MATERIAL\",\"storageUnitCode\":\"KG\",\"baseUnitCode\":\"G\",\"minStock\":1.000000,\"requiresLot\":false,\"requiresHeatNumber\":false,\"requiresExpiry\":false,\"requiresSerial\":false,\"active\":true,\"version\":0},\"conversions\":[{\"fromUnitCode\":\"KG\",\"toUnitCode\":\"G\",\"factor\":1000.000000,\"active\":true}]}";
        jdbc.update("INSERT INTO catalog.outbox_event(id,aggregate_type,aggregate_id,event_type,schema_version,correlation_id,payload,occurred_at) VALUES (?,'Product',?,'ProductCreated',1,?,?::jsonb,?)", id, product, correlation, payload, java.sql.Timestamp.from(Instant.now()));
        return id;
    }
}
