package utp.siga.catalog.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
        int schemaVersion, UUID correlationId, UUID causationId, JsonNode payload, Instant occurredAt, int attempts) { }
