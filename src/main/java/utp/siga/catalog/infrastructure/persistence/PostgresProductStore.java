package utp.siga.catalog.infrastructure.persistence;

import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductDetails;
import utp.siga.catalog.domain.model.UnitConversion;

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

    @Override
    public ListProductsUseCase.Page findPage(ListProductsUseCase.Query query) {
        var where = new StringBuilder(" WHERE 1=1"); var values = new ArrayList<Object>();
        filters(query, where, values);
        long total = jdbc.queryForObject("SELECT count(*) FROM catalog.product" + where, Long.class, values.toArray());
        String order = switch (query.sort()) {
            case SKU_ASC -> "sku ASC, id ASC"; case SKU_DESC -> "sku DESC, id ASC";
            case NAME_ASC -> "name ASC, id ASC"; case NAME_DESC -> "name DESC, id ASC";
        };
        var products = jdbc.query("SELECT id,sku,name,category_id,product_type,storage_unit_id,base_unit_id,min_stock,requires_lot,requires_heat_number,requires_expiry,requires_serial,technical_attributes,active,version FROM catalog.product" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (row, index) -> productRow(row), append(values, query.size(), Math.multiplyExact((long) query.page(), query.size())));
        return new ListProductsUseCase.Page(withConversions(products), query.page(), query.size(), total);
    }

    @Override
    public Optional<ProductDetails> findById(UUID id) {
        var products = jdbc.query("SELECT id,sku,name,category_id,product_type,storage_unit_id,base_unit_id,min_stock,requires_lot,requires_heat_number,requires_expiry,requires_serial,technical_attributes,active,version FROM catalog.product WHERE id=?",
                (row, index) -> productRow(row), id);
        return products.isEmpty() ? Optional.empty() : Optional.of(withConversions(products).getFirst());
    }

    private void filters(ListProductsUseCase.Query query, StringBuilder where, List<Object> values) {
        if (query.q() != null && !query.q().isEmpty()) { where.append(" AND (sku ILIKE ? ESCAPE E'\\\\' OR name ILIKE ? ESCAPE E'\\\\')"); String q="%" + query.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; values.add(q); values.add(q); }
        if (query.active() != null) { where.append(" AND active=?"); values.add(query.active()); }
        if (query.categoryId() != null) { where.append(" AND category_id=?"); values.add(query.categoryId()); }
        if (query.productType() != null) { where.append(" AND product_type=?"); values.add(query.productType().name()); }
    }
    private Object[] append(List<Object> values, Object... extra) { var all=new ArrayList<>(values); java.util.Collections.addAll(all,extra); return all.toArray(); }
    private List<ProductDetails> withConversions(List<ProductRow> products) {
        if (products.isEmpty()) return List.of();
        String marks=String.join(",", java.util.Collections.nCopies(products.size(), "?")); var ids=products.stream().map(ProductRow::id).toList();
        var conversions=jdbc.query("SELECT product_id,from_unit_id,to_unit_id,factor,active FROM catalog.unit_conversion WHERE product_id IN ("+marks+") ORDER BY product_id,id", (row,index) -> Map.entry(row.getObject("product_id",UUID.class), new UnitConversion(row.getObject("from_unit_id",UUID.class),row.getObject("to_unit_id",UUID.class),row.getBigDecimal("factor"),row.getBoolean("active"))), ids.toArray());
        var grouped=new LinkedHashMap<UUID,List<UnitConversion>>(); for(var pair:conversions) grouped.computeIfAbsent(pair.getKey(), ignored -> new ArrayList<>()).add(pair.getValue());
        return products.stream().map(p -> new ProductDetails(p.id(), product(p, grouped.getOrDefault(p.id(),List.of())),p.active(),p.version())).toList();
    }
    private ProductRow productRow(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ProductRow(row.getObject("id",UUID.class), row.getString("sku"),row.getString("name"),row.getObject("category_id",UUID.class),Product.Type.valueOf(row.getString("product_type")),row.getObject("storage_unit_id",UUID.class),row.getObject("base_unit_id",UUID.class),row.getBigDecimal("min_stock"),row.getBoolean("requires_lot"),row.getBoolean("requires_heat_number"),row.getBoolean("requires_expiry"),row.getBoolean("requires_serial"),parse(row.getString("technical_attributes")),row.getBoolean("active"),row.getLong("version"));
    }
    private Product product(ProductRow p,List<UnitConversion> conversions) { return new Product(p.sku(),p.name(),p.categoryId(),p.productType(),p.storageUnitId(),p.baseUnitId(),p.minStock(),p.requiresLot(),p.requiresHeatNumber(),p.requiresExpiry(),p.requiresSerial(),p.technicalAttributes(),conversions); }
    private com.fasterxml.jackson.databind.JsonNode parse(String value) { try { return new ObjectMapper().readTree(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private record ProductRow(UUID id, String sku, String name, UUID categoryId, Product.Type productType,
                              UUID storageUnitId, UUID baseUnitId, java.math.BigDecimal minStock,
                              boolean requiresLot, boolean requiresHeatNumber, boolean requiresExpiry,
                              boolean requiresSerial, com.fasterxml.jackson.databind.JsonNode technicalAttributes,
                              boolean active, long version) { }

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
