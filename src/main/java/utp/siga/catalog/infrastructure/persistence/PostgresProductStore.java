package utp.siga.catalog.infrastructure.persistence;

import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductSnapshot;
import utp.siga.catalog.domain.model.UnitConversion;

@Repository
public class PostgresProductStore implements ProductStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String SELECT_PRODUCT = """
            SELECT p.*, COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'fromUnitId', c.from_unit_id, 'toUnitId', c.to_unit_id,
                    'factor', c.factor, 'active', c.active)
                    ORDER BY c.from_unit_id, c.to_unit_id)
                FROM catalog.unit_conversion c WHERE c.product_id = p.id
            ), '[]'::jsonb) AS conversions_json
            FROM catalog.product p
            """;

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
                id, correlationId, payload(id, product, unitCodes, true, 0L));
        return id;
    }

    @Override
    public List<ProductSnapshot> findAllOrderBySku() {
        return jdbc.query(SELECT_PRODUCT + " ORDER BY p.sku, p.id", this::snapshot);
    }

    @Override
    public Optional<ProductSnapshot> findById(UUID id) {
        return jdbc.query(SELECT_PRODUCT + " WHERE p.id = ?", this::snapshot, id).stream().findFirst();
    }

    @Override
    public boolean lock(UUID id) {
        return !jdbc.query("SELECT id FROM catalog.product WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject("id", UUID.class), id).isEmpty();
    }

    @Override
    public ProductSnapshot update(UUID id, Product product, UUID correlationId, Map<UUID, String> unitCodes) {
        // The application holds the product row lock until product, conversions and outbox commit together.
        ProductSnapshot updated = jdbc.queryForObject("""
                UPDATE catalog.product SET sku=?, name=?, category_id=?, product_type=?,
                    storage_unit_id=?, base_unit_id=?, min_stock=?, requires_lot=?,
                    requires_heat_number=?, requires_expiry=?, requires_serial=?, technical_attributes=?::jsonb,
                    version=version+1, updated_at=now()
                WHERE id=? RETURNING active, version
                """,
                (row, index) -> new ProductSnapshot(id, row.getBoolean("active"), row.getLong("version"), product),
                product.sku(), product.name(), product.categoryId(), product.productType().name(),
                product.storageUnitId(), product.baseUnitId(), product.minStock(), product.requiresLot(),
                product.requiresHeatNumber(), product.requiresExpiry(), product.requiresSerial(),
                product.technicalAttributes().toString(), id);
        replaceConversions(id, product);
        jdbc.update("""
                INSERT INTO catalog.outbox_event(aggregate_type,aggregate_id,event_type,schema_version,correlation_id,payload)
                VALUES ('Product',?,'ProductUpdated',1,?,?::jsonb)
                """, id, correlationId, payload(id, product, unitCodes, updated.active(), updated.version()));
        return updated;
    }

    private void replaceConversions(UUID id, Product product) {
        var retained = new ArrayList<UUID>();
        for (var conversion : product.conversions()) {
            retained.add(jdbc.queryForObject("""
                    INSERT INTO catalog.unit_conversion(product_id,from_unit_id,to_unit_id,factor,active)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (product_id,from_unit_id,to_unit_id) DO UPDATE
                    SET factor=EXCLUDED.factor, active=EXCLUDED.active,
                        version=catalog.unit_conversion.version+1, updated_at=now()
                    RETURNING id
                    """, UUID.class, id, conversion.fromUnitId(), conversion.toUnitId(),
                    conversion.factor(), conversion.active()));
        }
        // PUT replaces the collection. Existing pairs retain their IDs; omitted pairs leave the collection.
        if (retained.isEmpty()) {
            jdbc.update("DELETE FROM catalog.unit_conversion WHERE product_id=?", id);
        } else {
            new NamedParameterJdbcTemplate(jdbc).update(
                    "DELETE FROM catalog.unit_conversion WHERE product_id=:product AND id NOT IN (:retained)",
                    Map.of("product", id, "retained", retained));
        }
    }

    private ProductSnapshot snapshot(ResultSet row, int index) throws SQLException {
        try {
            var conversions = new ArrayList<UnitConversion>();
            for (var node : json.readTree(row.getString("conversions_json"))) {
                conversions.add(new UnitConversion(UUID.fromString(node.get("fromUnitId").asText()),
                        UUID.fromString(node.get("toUnitId").asText()), node.get("factor").decimalValue(),
                        node.get("active").asBoolean()));
            }
            var product = new Product(row.getString("sku"), row.getString("name"),
                    row.getObject("category_id", UUID.class), Product.Type.valueOf(row.getString("product_type")),
                    row.getObject("storage_unit_id", UUID.class), row.getObject("base_unit_id", UUID.class),
                    row.getBigDecimal("min_stock"), row.getBoolean("requires_lot"),
                    row.getBoolean("requires_heat_number"), row.getBoolean("requires_expiry"),
                    row.getBoolean("requires_serial"), json.readTree(row.getString("technical_attributes")), conversions);
            return new ProductSnapshot(row.getObject("id", UUID.class), row.getBoolean("active"),
                    row.getLong("version"), product);
        } catch (JsonProcessingException error) {
            throw new SQLException("No se pudo leer el JSON del producto", error);
        }
    }

    private String payload(UUID id, Product product, Map<UUID, String> unitCodes, boolean active, long version) {
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
        item.put("active", active);
        item.put("version", version);
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
