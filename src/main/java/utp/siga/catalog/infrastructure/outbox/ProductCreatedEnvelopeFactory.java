package utp.siga.catalog.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProductCreatedEnvelopeFactory {
    private final ObjectMapper json;
    public ProductCreatedEnvelopeFactory(ObjectMapper json) { this.json = json; }

    public String create(OutboxEvent event) {
        if (!"ProductCreated".equals(event.eventType()) || !"Product".equals(event.aggregateType())
                || event.schemaVersion() != 1 || event.correlationId() == null || !validPayload(event.payload()))
            throw new IllegalArgumentException("Outbox event does not conform to ProductCreated v1");
        ObjectNode root = json.createObjectNode();
        root.put("eventId", event.id().toString());
        root.put("eventType", event.eventType());
        root.put("schemaVersion", event.schemaVersion());
        root.put("aggregateType", event.aggregateType());
        root.put("aggregateId", event.aggregateId().toString());
        root.put("occurredAt", event.occurredAt().toString());
        root.put("correlationId", event.correlationId().toString());
        if (event.causationId() != null) root.put("causationId", event.causationId().toString());
        root.put("producer", "catalog-service");
        root.set("payload", event.payload());
        return root.toString();
    }

    private boolean validPayload(JsonNode payload) {
        if (!payload.isObject() || !payload.path("conversions").isArray()) return false;
        JsonNode p = payload.path("product");
        String[] required = {"id", "sku", "productType", "storageUnitCode", "baseUnitCode", "minStock", "requiresLot", "requiresHeatNumber", "requiresExpiry", "requiresSerial", "active", "version"};
        for (String field : required) if (p.path(field).isMissingNode() || p.path(field).isNull()) return false;
        if (!uuid(p.path("id")) || !text(p.path("sku"), 80)
                || !Set.of("MATERIAL", "INSUMO", "REPUESTO", "MAQUINARIA").contains(p.path("productType").asText())
                || !text(p.path("storageUnitCode"), 20) || !text(p.path("baseUnitCode"), 20)
                || !decimal(p.path("minStock"), false) || !p.path("active").isBoolean()
                || !p.path("version").canConvertToLong() || p.path("version").asLong() < 0
                || !p.path("requiresLot").isBoolean() || !p.path("requiresHeatNumber").isBoolean()
                || !p.path("requiresExpiry").isBoolean() || !p.path("requiresSerial").isBoolean()) return false;
        for (JsonNode conversion : payload.path("conversions"))
            if (!text(conversion.path("fromUnitCode"), 20) || !text(conversion.path("toUnitCode"), 20)
                    || !decimal(conversion.path("factor"), true) || !conversion.path("active").isBoolean()) return false;
        return true;
    }

    private boolean uuid(JsonNode value) { try { UUID.fromString(value.asText()); return value.isTextual(); } catch (IllegalArgumentException ex) { return false; } }
    private boolean text(JsonNode value, int maximum) { return value.isTextual() && value.textValue().length() <= maximum; }
    private boolean decimal(JsonNode value, boolean strictlyPositive) {
        if (!value.isNumber()) return false;
        BigDecimal number = value.decimalValue();
        return number.scale() <= 6 && (strictlyPositive ? number.signum() > 0 : number.signum() >= 0);
    }
}
