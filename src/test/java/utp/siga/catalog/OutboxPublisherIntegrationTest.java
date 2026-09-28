package utp.siga.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final List<String> queues = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach void isolateOutbox() { }

    @AfterEach void cleanup() {
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        for (String queue : queues) admin.deleteQueue(queue);
        for (UUID eventId : eventIds) jdbc.update("DELETE FROM catalog.outbox_event WHERE id=?", eventId);
    }

    @Test void publishesPersistentCanonicalEnvelopeOnlyAfterConfirm() throws Exception {
        String queue = queue("catalog.product.created.v1"); UUID id = pending();
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
        String queue = queue("catalog.product.created.v1"); UUID id = pending();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(publisher::publishNext); var second = workers.submit(publisher::publishNext);
            assertTrue(first.get() || second.get());
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM catalog.outbox_event WHERE id=?", String.class, id));
            int ownMessages = 0;
            for (int index = 0; index < 2; index++) {
                var message = rabbit.receive(queue, index == 0 ? 5000 : 300);
                if (message != null && id.toString().equals(json.readTree(message.getBody()).path("eventId").asText())) ownMessages++;
            }
            assertEquals(1, ownMessages);
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
        String queue = queue("catalog.product.created.v1"); UUID id = pending();
        jdbc.update("UPDATE catalog.outbox_event SET attempts=1,next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", id);
        assertTrue(publisher.publishNext());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM catalog.outbox_event WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM catalog.outbox_event WHERE id=?", Integer.class, id));
        assertNotNull(rabbit.receive(queue, 5000));
    }

    @Test void validatesRealSupplierEnvelopesAgainstTheirVersionedSchemas() throws Exception {
        for (String eventType : List.of("SupplierCreated", "SupplierUpdated", "SupplierDisabled")) {
            UUID id = supplierPending(eventType, !eventType.equals("SupplierDisabled"));
            JsonNode envelope = json.readTree(envelopes.create(event(id)));
            assertSchema(eventType, envelope);
            assertEquals(id.toString(), envelope.path("eventId").asText());
            assertEquals(event(id).aggregateId().toString(), envelope.path("aggregateId").asText());
            assertEquals(1, envelope.path("schemaVersion").asInt());
            assertEquals(event(id).correlationId().toString(), envelope.path("correlationId").asText());
            assertTrue(envelope.path("occurredAt").isTextual());
            assertEquals(eventType, envelope.path("eventType").asText());
            if (eventType.equals("SupplierDisabled")) assertFalse(envelope.path("payload").path("supplier").path("active").asBoolean());
        }
    }

    @Test void publishesSupplierEventsWithApprovedRoutingPersistentMessagesAndConfirms() throws Exception {
        List<String> eventTypes = List.of("SupplierCreated", "SupplierUpdated", "SupplierDisabled");
        String queue = "catalog.publisher.supplier.test." + UUID.randomUUID();
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.declareQueue(new Queue(queue, true, false, false));
        queues.add(queue);
        for (String eventType : eventTypes) admin.declareBinding(BindingBuilder.bind(new Queue(queue))
                .to(new org.springframework.amqp.core.TopicExchange("siga.events")).with(routing(eventType)));
        List<UUID> ids = new ArrayList<>();
        for (String eventType : eventTypes) ids.add(supplierPending(eventType, !eventType.equals("SupplierDisabled")));

        for (int index = 0; index < eventTypes.size(); index++) assertTrue(publisher.publishNext());
        Set<String> received = new HashSet<>();
        for (int index = 0; index < eventTypes.size(); index++) {
            var message = rabbit.receive(queue, 5000);
            assertNotNull(message);
            assertEquals("PERSISTENT", message.getMessageProperties().getReceivedDeliveryMode().name());
            JsonNode envelope = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            received.add(envelope.path("eventType").asText());
            assertSchema(envelope.path("eventType").asText(), envelope);
        }
        assertEquals(Set.copyOf(eventTypes), received);
        for (UUID id : ids) assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM catalog.outbox_event WHERE id=?", String.class, id));
    }

    @Test void keepsSupplierPendingAfterBrokerFailureAndRecoversDueRetry() {
        UUID id = supplierPending("SupplierUpdated", true);
        CachingConnectionFactory unavailableConnection = new CachingConnectionFactory("127.0.0.1", 1);
        try {
            OutboxPublisher unavailable = new OutboxPublisher(transaction, events, envelopes, new RabbitTemplate(unavailableConnection),
                    "siga.events", "catalog.product.created.v1", 100, 1000, 1000);
            assertFalse(unavailable.publishNext());
        } finally { unavailableConnection.destroy(); }
        var failed = jdbc.queryForMap("SELECT status,attempts,next_attempt_at > CURRENT_TIMESTAMP AS delayed FROM catalog.outbox_event WHERE id=?", id);
        assertEquals("PENDING", failed.get("status")); assertEquals(1, failed.get("attempts")); assertEquals(true, failed.get("delayed"));
        String queue = queue("catalog.supplier.updated.v1");
        jdbc.update("UPDATE catalog.outbox_event SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", id);
        assertTrue(publisher.publishNext());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM catalog.outbox_event WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM catalog.outbox_event WHERE id=?", Integer.class, id));
        assertNotNull(rabbit.receive(queue, 5000));
    }

    private String queue(String routingKey) {
        String queue = "catalog.publisher.test." + UUID.randomUUID(); RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.declareQueue(new Queue(queue, true, false, false));
        admin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(new org.springframework.amqp.core.TopicExchange("siga.events")).with(routingKey));
        queues.add(queue);
        return queue;
    }
    private UUID pending() {
        UUID id=UUID.randomUUID(), product=UUID.randomUUID(), correlation=UUID.randomUUID();
        String payload = "{\"product\":{\"id\":\""+product+"\",\"sku\":\"PUB-"+id.toString().substring(0,8)+"\",\"productType\":\"MATERIAL\",\"storageUnitCode\":\"KG\",\"baseUnitCode\":\"G\",\"minStock\":1.000000,\"requiresLot\":false,\"requiresHeatNumber\":false,\"requiresExpiry\":false,\"requiresSerial\":false,\"active\":true,\"version\":0},\"conversions\":[{\"fromUnitCode\":\"KG\",\"toUnitCode\":\"G\",\"factor\":1000.000000,\"active\":true}]}";
        jdbc.update("INSERT INTO catalog.outbox_event(id,aggregate_type,aggregate_id,event_type,schema_version,correlation_id,payload,occurred_at) VALUES (?,'Product',?,'ProductCreated',1,?,?::jsonb,?)", id, product, correlation, payload, java.sql.Timestamp.from(Instant.EPOCH));
        eventIds.add(id);
        return id;
    }
    private UUID supplierPending(String eventType, boolean active) {
        UUID id=UUID.randomUUID(), supplier=UUID.randomUUID(), correlation=UUID.randomUUID();
        String payload = "{\"supplier\":{\"id\":\""+supplier+"\",\"code\":\"SUP-"+id.toString().substring(0,8)+"\",\"taxId\":\"T-"+id.toString().substring(0,8)+"\",\"businessName\":\"Proveedor prueba\",\"active\":"+active+",\"version\":1}}";
        jdbc.update("INSERT INTO catalog.outbox_event(id,aggregate_type,aggregate_id,event_type,schema_version,correlation_id,payload,occurred_at) VALUES (?,'Supplier',?,?,1,?,?::jsonb,?)", id, supplier, eventType, correlation, payload, java.sql.Timestamp.from(Instant.EPOCH));
        eventIds.add(id);
        return id;
    }
    private utp.siga.catalog.infrastructure.outbox.OutboxEvent event(UUID id) {
        return jdbc.queryForObject("SELECT id,aggregate_type,aggregate_id,event_type,schema_version,correlation_id,causation_id,payload,occurred_at,attempts FROM catalog.outbox_event WHERE id=?", (rs, row) -> {
            try {
                return new utp.siga.catalog.infrastructure.outbox.OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getInt("schema_version"), rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class), json.readTree(rs.getString("payload")), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts"));
            } catch (java.io.IOException exception) {
                throw new SQLException("Invalid outbox JSON", exception);
            }
        }, id);
    }
    private void assertSchema(String eventType, JsonNode envelope) throws Exception {
        String filename = switch (eventType) { case "SupplierCreated" -> "supplier-created-v1.schema.json"; case "SupplierUpdated" -> "supplier-updated-v1.schema.json"; case "SupplierDisabled" -> "supplier-disabled-v1.schema.json"; default -> "product-created-v1.schema.json"; };
        Path path = Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api", filename);
        JsonNode schema = json.readTree(Files.readString(path));
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        assertTrue(validator.validate(envelope).isEmpty(), () -> validator.validate(envelope).toString());
    }
    private String routing(String eventType) { return switch(eventType) { case "SupplierCreated" -> "catalog.supplier.created.v1"; case "SupplierUpdated" -> "catalog.supplier.updated.v1"; case "SupplierDisabled" -> "catalog.supplier.disabled.v1"; default -> "catalog.product.created.v1"; }; }
}
