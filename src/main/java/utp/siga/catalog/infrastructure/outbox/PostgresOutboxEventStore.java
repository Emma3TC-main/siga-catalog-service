package utp.siga.catalog.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresOutboxEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresOutboxEventStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<OutboxEvent> claimNextDueEvent() {
        return jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, schema_version, correlation_id, causation_id, payload, occurred_at, attempts
                FROM catalog.outbox_event
                WHERE status = 'PENDING' AND event_type IN ('ProductCreated','ProductUpdated','SupplierCreated','SupplierUpdated','SupplierDisabled')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                ORDER BY occurred_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                """, rs -> rs.next() ? Optional.of(new OutboxEvent(
                rs.getObject("id", java.util.UUID.class), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", java.util.UUID.class), rs.getString("event_type"), rs.getInt("schema_version"),
                rs.getObject("correlation_id", java.util.UUID.class), rs.getObject("causation_id", java.util.UUID.class),
                parse(rs.getString("payload")), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts"))) : Optional.empty());
    }

    public void markPublished(OutboxEvent event) {
        jdbc.update("UPDATE catalog.outbox_event SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", event.id());
    }

    public void scheduleRetry(OutboxEvent event, long delayMillis) {
        jdbc.update("""
                UPDATE catalog.outbox_event SET attempts=attempts+1,
                    next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                WHERE id=? AND status='PENDING'
                """, delayMillis, event.id());
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Outbox payload is not JSON", ex); }
    }
}
