package utp.siga.catalog.infrastructure.persistence;

import java.util.UUID;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.domain.model.Product;

@Repository
public class PostgresProductStore implements ProductStore {
    private final JdbcTemplate jdbc;

    public PostgresProductStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Product product, UUID correlationId, Map<UUID, String> unitCodes) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO catalog.product (sku,name,category_id,product_type,storage_unit_id,base_unit_id,min_stock,
                  requires_lot,requires_heat_number,requires_expiry,requires_serial,technical_attributes)
                VALUES (?,?,?,?,?,?,?, ?,?,?,?, ?::jsonb) RETURNING id
                """, UUID.class, product.sku(), product.name(), product.categoryId(), product.productType().name(),
                product.storageUnitId(), product.baseUnitId(), product.minStock(), product.requiresLot(),
                product.requiresHeatNumber(), product.requiresExpiry(), product.requiresSerial(),
                product.technicalAttributes().toString());
        for (var conversion : product.conversions())
            jdbc.update(
                    "INSERT INTO catalog.unit_conversion(product_id,from_unit_id,to_unit_id,factor,active) VALUES (?,?,?,?,?)",
                    id, conversion.fromUnitId(), conversion.toUnitId(), conversion.factor(), conversion.active());
        jdbc.update(
                """
                        INSERT INTO catalog.outbox_event(aggregate_type,aggregate_id,event_type,schema_version,correlation_id,payload)
                        VALUES ('Product',?,'ProductCreated',1,?,?::jsonb)
                        """,
                id, correlationId, payload(id, product, unitCodes));
        return id;
    }

    private String payload(UUID id, Product product, Map<UUID, String> unitCodes) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        ObjectNode item = root.putObject("product");
        item.put("id", id.toString());
        item.put("sku", product.sku());
        item.put("productType", product.productType().name());
        item.put("storageUnitCode", unitCodes.get(product.storageUnitId()));
        item.put("baseUnitCode", unitCodes.get(product.baseUnitId()));
        item.put("minStock", product.minStock());
        item.put("requiresLot", product.requiresLot());
        item.put("requiresHeatNumber", product.requiresHeatNumber());
        item.put("requiresExpiry", product.requiresExpiry());
        item.put("requiresSerial", product.requiresSerial());
        item.put("active", true);
        item.put("version", 0L);
        ArrayNode conversions = root.putArray("conversions");
        for (var conversion : product.conversions()) {
            ObjectNode value = conversions.addObject();
            value.put("fromUnitCode", unitCodes.get(conversion.fromUnitId()));
            value.put("toUnitCode", unitCodes.get(conversion.toUnitId()));
            value.put("factor", conversion.factor());
            value.put("active", conversion.active());
        }
        return root.toString();
    }
}
