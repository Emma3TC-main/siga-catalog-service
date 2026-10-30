package utp.siga.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.catalog.application.port.in.UpdateProductCommand;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductVersionConflictException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductUpdateIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("product-update-test-only").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            JWKS.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.security.jwks-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add("catalog.outbox.publisher.enabled", () -> "false");
    }

    @org.junit.jupiter.api.AfterAll
    static void shutdown() {
        JWKS.stop(0);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UpdateProductUseCase products;

    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> categoryIds = new ArrayList<>();
    private final List<UUID> unitIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_product_updated_test ON catalog.outbox_event");
        jdbc.execute("DROP FUNCTION IF EXISTS catalog.reject_product_updated_test()");
        productIds.forEach(id -> jdbc.update("DELETE FROM catalog.outbox_event WHERE aggregate_id=?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM catalog.unit_conversion WHERE product_id=?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM catalog.product WHERE id=?", id));
        unitIds.forEach(id -> jdbc.update("DELETE FROM catalog.unit_measure WHERE id=?", id));
        categoryIds.forEach(id -> jdbc.update("DELETE FROM catalog.category WHERE id=?", id));
    }

    @Test
    void replacesCompleteProductAndConversionsWithPendingCanonicalOutbox() throws Exception {
        Fixture value = fixture();
        UUID extra = unit(true);
        ObjectNode request = request(value, 0, true);
        request.put("sku", "UPDATED-" + value.id());
        request.put("name", "Producto actualizado");
        request.put("minStock", new BigDecimal("15.250000"));
        request.put("requiresHeatNumber", true);
        request.putObject("technicalAttributes").put("grade", "B").put("size", 2);
        ArrayNode conversions = request.putArray("conversions");
        conversion(conversions, value.storage(), value.base(), "3.000000", true);
        conversion(conversions, extra, value.base(), "4.500000", false);

        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .header("X-Correlation-ID", value.correlation()).contentType("application/json")
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("sku").value("UPDATED-" + value.id()))
                .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("conversions.length()").value(2));

        Map<String, Object> product = jdbc.queryForMap(
                "SELECT sku,name,min_stock,requires_heat_number,version,active FROM catalog.product WHERE id=?",
                value.id());
        assertEquals("UPDATED-" + value.id(), product.get("sku"));
        assertEquals(1L, product.get("version"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM catalog.unit_conversion WHERE product_id=?", Integer.class, value.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.unit_conversion WHERE product_id=? AND from_unit_id=?",
                Integer.class, value.id(), value.base()));

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type,schema_version,status,correlation_id,payload FROM catalog.outbox_event WHERE aggregate_id=?",
                value.id());
        assertEquals("ProductUpdated", event.get("event_type"));
        assertEquals(1, event.get("schema_version"));
        assertEquals("PENDING", event.get("status"));
        assertEquals(value.correlation(), event.get("correlation_id"));
        JsonNode payload = json.readTree(event.get("payload").toString());
        assertEquals(value.id().toString(), payload.path("product").path("id").asText());
        assertEquals(value.categoryCode(), payload.path("product").path("categoryCode").asText());
        assertEquals("Producto actualizado", payload.path("product").path("name").asText());
        assertEquals(1, payload.path("product").path("version").asLong());
        assertEquals(2, payload.path("conversions").size());
        assertTrue(payload.toString().contains("storageUnitCode"));
    }

    @Test
    void rejectsInvalidAuthenticationAuthorizationBodyAndMissingProduct() throws Exception {
        Fixture value = fixture();
        ObjectNode valid = request(value, 0, true);
        mvc.perform(put("/api/v1/products/{id}", value.id()).contentType("application/json")
                        .content(json.writeValueAsBytes(valid)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH_INVALID"));
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("INVENTORY_READ"))
                        .contentType("application/json").content(json.writeValueAsBytes(valid)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("AUTH_FORBIDDEN"));
        valid.remove("expectedVersion");
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(valid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/v1/products/{id}", UUID.randomUUID())
                        .header("Authorization", bearer("PRODUCT_WRITE")).contentType("application/json")
                        .content(json.writeValueAsBytes(request(value, 0, true))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("PRODUCT_NOT_FOUND"));
        assertEquals(0, outbox(value.id()));
    }

    @Test
    void staleVersionWinsBeforeNoOpAndValidNoOpIgnoresInactiveHistoricalReferences() throws Exception {
        Fixture value = fixture();
        jdbc.update("UPDATE catalog.category SET active=false WHERE id=?", value.category());
        jdbc.update("UPDATE catalog.unit_measure SET active=false WHERE id IN (?,?)", value.storage(), value.base());
        Map<String, Object> before = jdbc.queryForMap("SELECT version,updated_at FROM catalog.product WHERE id=?",
                value.id());
        List<Map<String, Object>> conversionsBefore = conversionRows(value.id());

        ObjectNode equivalent = request(value, 99, true);
        reverseAndRescale(equivalent);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(equivalent)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("VERSION_CONFLICT"));

        equivalent.put("expectedVersion", 0);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(equivalent)))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(0));
        assertEquals(before, jdbc.queryForMap("SELECT version,updated_at FROM catalog.product WHERE id=?", value.id()));
        assertEquals(conversionsBefore, conversionRows(value.id()));
        assertEquals(0, outbox(value.id()));
    }

    @Test
    void validatesMissingAndInactiveNewReferencesWithoutWrites() throws Exception {
        Fixture value = fixture();
        ObjectNode missingCategory = request(value, 0, true);
        missingCategory.put("categoryId", UUID.randomUUID().toString());
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(missingCategory)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("CATEGORY_NOT_FOUND"));

        UUID inactiveCategory = category(false);
        ObjectNode inactive = request(value, 0, true);
        inactive.put("categoryId", inactiveCategory.toString());
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(inactive)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CATEGORY_INACTIVE"));

        UUID missingUnit = UUID.randomUUID();
        ObjectNode missingUnitRequest = request(value, 0, true);
        missingUnitRequest.put("storageUnitId", missingUnit.toString());
        ArrayNode missingConversions = missingUnitRequest.putArray("conversions");
        conversion(missingConversions, missingUnit, value.base(), "2", true);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(missingUnitRequest)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("UNIT_OF_MEASURE_NOT_FOUND"));

        UUID inactiveUnit = unit(false);
        ObjectNode invalidConversion = request(value, 0, false);
        conversion((ArrayNode) invalidConversion.path("conversions"), inactiveUnit, value.base(), "1", false);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(invalidConversion)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("UNIT_OF_MEASURE_INACTIVE"));
        assertEquals(0L, version(value.id()));
        assertEquals(0, outbox(value.id()));
    }

    @Test
    void deactivatesWithInactiveHistoricalReferencesButRequiresValidReferencesToReactivate() throws Exception {
        Fixture value = fixture();
        jdbc.update("UPDATE catalog.category SET active=false WHERE id=?", value.category());
        jdbc.update("UPDATE catalog.unit_measure SET active=false WHERE id IN (?,?)", value.storage(), value.base());

        ObjectNode alteredHistoricalReference = request(value, 0, false);
        ((ObjectNode) alteredHistoricalReference.path("conversions").get(0)).put("factor", 3);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(alteredHistoricalReference)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("UNIT_OF_MEASURE_INACTIVE"));
        assertEquals(0L, version(value.id()));
        assertEquals(0, outbox(value.id()));

        ObjectNode deactivate = request(value, 0, false);
        deactivate.put("name", "Producto desactivado");
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(deactivate)))
                .andExpect(status().isOk()).andExpect(jsonPath("active").value(false))
                .andExpect(jsonPath("version").value(1));

        ObjectNode reactivate = request(value, 1, true);
        reactivate.put("name", "Producto desactivado");
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(reactivate)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CATEGORY_INACTIVE"));
        assertEquals(1L, version(value.id()));
        assertEquals(1, outbox(value.id()));
    }

    @Test
    void rejectsDuplicateSkuAndInvalidConversionsWithCompleteRollback() throws Exception {
        Fixture value = fixture();
        Fixture occupied = fixture();
        ObjectNode duplicate = request(value, 0, true);
        duplicate.put("sku", occupied.sku());
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(duplicate)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DATA_CONFLICT"));

        ObjectNode invalid = request(value, 0, true);
        ((ObjectNode) invalid.path("conversions").get(0)).put("factor", 0);
        mvc.perform(put("/api/v1/products/{id}", value.id()).header("Authorization", bearer("PRODUCT_WRITE"))
                        .contentType("application/json").content(json.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        assertEquals(value.sku(), jdbc.queryForObject("SELECT sku FROM catalog.product WHERE id=?", String.class,
                value.id()));
        assertEquals(0L, version(value.id()));
        assertEquals(0, outbox(value.id()));
    }

    @Test
    void serializesConcurrentUpdatesWithSameExpectedVersion() throws Exception {
        Fixture value = fixture();
        UpdateProductCommand first = command(value, "Concurrente A");
        UpdateProductCommand second = command(value, "Concurrente B");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            Callable<Object> a = () -> executeConcurrent(value.id(), first, ready, start);
            Callable<Object> b = () -> executeConcurrent(value.id(), second, ready, start);
            var fa = executor.submit(a);
            var fb = executor.submit(b);
            ready.await();
            start.countDown();
            List<Object> results = List.of(fa.get(), fb.get());
            assertEquals(1, results.stream().filter(ProductVersionConflictException.class::isInstance).count());
            assertEquals(1, results.stream().filter(utp.siga.catalog.domain.model.ProductDetails.class::isInstance).count());
        }
        assertEquals(1L, version(value.id()));
        assertEquals(1, outbox(value.id()));
    }

    @Test
    void rollsBackProductConversionsAndVersionWhenOutboxInsertFails() throws Exception {
        Fixture value = fixture();
        Map<String, Object> productBefore = jdbc.queryForMap("SELECT name,version,updated_at FROM catalog.product WHERE id=?",
                value.id());
        List<Map<String, Object>> conversionsBefore = conversionRows(value.id());
        jdbc.execute("""
                CREATE FUNCTION catalog.reject_product_updated_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.event_type='ProductUpdated' THEN RAISE EXCEPTION 'forced test rollback'; END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_product_updated_test BEFORE INSERT ON catalog.outbox_event
                FOR EACH ROW EXECUTE FUNCTION catalog.reject_product_updated_test()
                """);

        ServletException failure = assertThrows(ServletException.class,
                () -> mvc.perform(put("/api/v1/products/{id}", value.id())
                        .header("Authorization", bearer("PRODUCT_WRITE")).contentType("application/json")
                        .content(json.writeValueAsBytes(changedRequest(value, "Debe revertirse")))).andReturn());
        assertInstanceOf(org.springframework.dao.DataAccessException.class, failure.getCause());
        assertEquals(productBefore, jdbc.queryForMap("SELECT name,version,updated_at FROM catalog.product WHERE id=?",
                value.id()));
        assertEquals(conversionsBefore, conversionRows(value.id()));
        assertEquals(0, outbox(value.id()));
    }

    private Object executeConcurrent(UUID id, UpdateProductCommand command, java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            return products.update(id, command);
        } catch (ProductVersionConflictException conflict) {
            return conflict;
        }
    }

    private Fixture fixture() {
        UUID category = category(true), storage = unit(true), base = unit(true), id = UUID.randomUUID();
        String sku = "UPD-" + id;
        jdbc.update("""
                INSERT INTO catalog.product(id,sku,name,category_id,product_type,storage_unit_id,base_unit_id,min_stock,
                    requires_lot,requires_heat_number,requires_expiry,requires_serial,technical_attributes,active,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,true,0)
                """, id, sku, "Producto original", category, "MATERIAL", storage, base,
                new BigDecimal("10.000000"), true, false, false, false, "{\"a\":1,\"b\":2}");
        jdbc.update("INSERT INTO catalog.unit_conversion(product_id,from_unit_id,to_unit_id,factor,active) VALUES (?,?,?,?,true)",
                id, storage, base, new BigDecimal("2.000000"));
        jdbc.update("INSERT INTO catalog.unit_conversion(product_id,from_unit_id,to_unit_id,factor,active) VALUES (?,?,?,?,false)",
                id, base, storage, new BigDecimal("0.500000"));
        productIds.add(id);
        String code = jdbc.queryForObject("SELECT code FROM catalog.category WHERE id=?", String.class, category);
        return new Fixture(id, sku, category, code, storage, base, UUID.randomUUID());
    }

    private UUID category(boolean active) {
        UUID id = UUID.randomUUID();
        categoryIds.add(id);
        jdbc.update("INSERT INTO catalog.category(id,code,name,category_type,active) VALUES (?,?,?,?,?)", id,
                "UC-" + id.toString().substring(0, 8), "Categoría actualización", "MATERIAL", active);
        return id;
    }

    private UUID unit(boolean active) {
        UUID id = UUID.randomUUID();
        unitIds.add(id);
        jdbc.update("INSERT INTO catalog.unit_measure(id,code,name,symbol,dimension,active) VALUES (?,?,?,?,?,?)", id,
                "UU" + id.toString().replace("-", "").substring(0, 12), "Unidad actualización", "u", "TEST", active);
        return id;
    }

    private ObjectNode request(Fixture value, long expectedVersion, boolean active) {
        ObjectNode request = json.createObjectNode();
        request.put("sku", value.sku());
        request.put("name", "Producto original");
        request.put("categoryId", value.category().toString());
        request.put("productType", "MATERIAL");
        request.put("storageUnitId", value.storage().toString());
        request.put("baseUnitId", value.base().toString());
        request.put("minStock", new BigDecimal("10.000000"));
        request.put("requiresLot", true);
        request.put("requiresHeatNumber", false);
        request.put("requiresExpiry", false);
        request.put("requiresSerial", false);
        request.putObject("technicalAttributes").put("a", 1).put("b", 2);
        request.put("active", active);
        request.put("expectedVersion", expectedVersion);
        ArrayNode conversions = request.putArray("conversions");
        conversion(conversions, value.storage(), value.base(), "2.000000", true);
        conversion(conversions, value.base(), value.storage(), "0.500000", false);
        return request;
    }

    private ObjectNode changedRequest(Fixture value, String name) {
        ObjectNode request = request(value, 0, true);
        request.put("name", name);
        request.put("minStock", new BigDecimal("11.000000"));
        return request;
    }

    private void reverseAndRescale(ObjectNode request) {
        ObjectNode attributes = request.putObject("technicalAttributes");
        attributes.put("b", new BigDecimal("2.00")).put("a", new BigDecimal("1.0"));
        ArrayNode old = (ArrayNode) request.remove("conversions");
        ArrayNode reordered = request.putArray("conversions");
        ObjectNode second = (ObjectNode) old.get(1);
        second.put("factor", new BigDecimal("0.5"));
        reordered.add(second);
        ObjectNode first = (ObjectNode) old.get(0);
        first.put("factor", new BigDecimal("2.0"));
        reordered.add(first);
    }

    private void conversion(ArrayNode values, UUID from, UUID to, String factor, boolean active) {
        ObjectNode value = values.addObject();
        value.put("fromUnitId", from.toString());
        value.put("toUnitId", to.toString());
        value.put("factor", new BigDecimal(factor));
        value.put("active", active);
    }

    private UpdateProductCommand command(Fixture value, String name) {
        return new UpdateProductCommand(value.sku(), name, value.category(), Product.Type.MATERIAL, value.storage(),
                value.base(), new BigDecimal("10"), true, false, false, false,
                json.createObjectNode().put("b", 2).put("a", 1),
                List.of(new UpdateProductCommand.Conversion(value.base(), value.storage(), new BigDecimal("0.5"), false),
                        new UpdateProductCommand.Conversion(value.storage(), value.base(), new BigDecimal("2"), true)),
                true, 0L, UUID.randomUUID());
    }

    private List<Map<String, Object>> conversionRows(UUID productId) {
        return jdbc.queryForList("""
                SELECT id,from_unit_id,to_unit_id,factor,active,version,created_at,updated_at
                FROM catalog.unit_conversion WHERE product_id=? ORDER BY from_unit_id,to_unit_id
                """, productId);
    }

    private long version(UUID productId) {
        return jdbc.queryForObject("SELECT version FROM catalog.product WHERE id=?", Long.class, productId);
    }

    private int outbox(UUID productId) {
        return jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id=?", Integer.class,
                productId);
    }

    private String bearer(String permission) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("siga-identity").audience("siga-api")
                .subject(UUID.randomUUID().toString()).issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("permissions", List.of(permission))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("product-update-test-only").build(),
                claims);
        jwt.sign(new RSASSASigner(KEY));
        return "Bearer " + jwt.serialize();
    }

    private record Fixture(UUID id, String sku, UUID category, String categoryCode, UUID storage, UUID base,
            UUID correlation) {
    }
}
