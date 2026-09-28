package utp.siga.catalog;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductFieldValidationException;
import utp.siga.catalog.domain.model.ProductReferenceException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductCreationIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("product-test-only").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            JWKS.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @org.junit.jupiter.api.AfterAll
    static void shutdown() { JWKS.stop(0); }
    @Autowired CreateProductUseCase products;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final List<UUID> categories = new ArrayList<>();
    private final List<UUID> units = new ArrayList<>();
    private final List<String> skus = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String sku : skus) {
            jdbc.update("DELETE FROM catalog.unit_conversion WHERE product_id IN (SELECT id FROM catalog.product WHERE sku=?)", sku);
            jdbc.update("DELETE FROM catalog.product WHERE sku=?", sku);
        }
        for (UUID id : units) jdbc.update("DELETE FROM catalog.unit_measure WHERE id=?", id);
        for (UUID id : categories) jdbc.update("DELETE FROM catalog.category WHERE id=?", id);
    }

    private UUID category(boolean active) {
        UUID id = UUID.randomUUID();
        categories.add(id);
        jdbc.update("INSERT INTO catalog.category(id,code,name,category_type,active) VALUES (?,?,?,?,?)", id,
                "PC-" + id.toString().substring(0, 8), "Categoría producto", "MATERIAL", active);
        return id;
    }

    private UUID unit(boolean active) {
        UUID id = UUID.randomUUID();
        units.add(id);
        jdbc.update("INSERT INTO catalog.unit_measure(id,code,name,symbol,dimension,active) VALUES (?,?,?,?,?,?)", id,
                "PU" + id.toString().replace("-", "").substring(0, 12), "Unidad producto", "u", "TEST", active);
        return id;
    }

    private CreateProductCommand command(String sku, UUID categoryId, UUID storageUnitId, UUID baseUnitId,
            List<CreateProductCommand.Conversion> conversions) {
        skus.add(sku);
        return new CreateProductCommand(sku, "Producto de prueba", categoryId, Product.Type.MATERIAL,
                storageUnitId, baseUnitId, new BigDecimal("12.345678"), null, null, null, null,
                JsonNodeFactory.instance.objectNode().put("grade", "A36"), conversions, UUID.randomUUID());
    }

    private String token(String permission) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("siga-identity").audience("siga-api").subject(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("permissions", List.of(permission)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("product-test-only").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private String json(String sku, UUID category, UUID storage, UUID base, String conversions) {
        return "{\"sku\":\"" + sku + "\",\"name\":\"Producto REST\",\"categoryId\":\"" + category
                + "\",\"productType\":\"MATERIAL\",\"storageUnitId\":\"" + storage + "\",\"baseUnitId\":\""
                + base + "\",\"minStock\":12.345678,\"technicalAttributes\":{\"grade\":\"A36\"},\"conversions\":" + conversions + "}";
    }

    @Test
    void persistsProductConversionsAndPendingProductCreatedOutboxInOneTransaction() {
        UUID category = category(true), storage = unit(true), base = unit(true);
        String sku = "P-" + UUID.randomUUID();
        int outboxBefore = jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event", Integer.class);

        var created = products.create(command(sku, category, storage, base,
                List.of(new CreateProductCommand.Conversion(storage, base, new BigDecimal("2.500000"), null))));

        var product = jdbc.queryForMap("SELECT category_id,storage_unit_id,base_unit_id,min_stock,active,version,technical_attributes FROM catalog.product WHERE sku=?", sku);
        assertEquals(category, product.get("category_id"));
        assertEquals(storage, product.get("storage_unit_id"));
        assertEquals(base, product.get("base_unit_id"));
        assertEquals(new BigDecimal("12.345678"), product.get("min_stock"));
        assertEquals(true, product.get("active"));
        assertEquals(0L, product.get("version"));
        assertEquals("A36", jdbc.queryForObject("SELECT technical_attributes->>'grade' FROM catalog.product WHERE sku=?", String.class, sku));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.unit_conversion c JOIN catalog.product p ON p.id=c.product_id WHERE p.sku=?", Integer.class, sku));
        assertEquals(outboxBefore + 1, jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event", Integer.class));
        var event = jdbc.queryForMap("SELECT aggregate_type,aggregate_id,event_type,schema_version,status,correlation_id,payload FROM catalog.outbox_event WHERE aggregate_id=?", created.id());
        assertEquals("Product", event.get("aggregate_type"));
        assertEquals(created.id(), event.get("aggregate_id"));
        assertEquals("ProductCreated", event.get("event_type"));
        assertEquals(1, event.get("schema_version"));
        assertEquals("PENDING", event.get("status"));
        assertEquals(created.id().toString(), jdbc.queryForObject("SELECT payload->'product'->>'id' FROM catalog.outbox_event WHERE aggregate_id=?", String.class, created.id()));
        assertEquals(jdbc.queryForObject("SELECT code FROM catalog.unit_measure WHERE id=?", String.class, storage),
                jdbc.queryForObject("SELECT payload->'product'->>'storageUnitCode' FROM catalog.outbox_event WHERE aggregate_id=?", String.class, created.id()));
        assertEquals("2.500000", jdbc.queryForObject("SELECT payload->'conversions'->0->>'factor' FROM catalog.outbox_event WHERE aggregate_id=?", String.class, created.id()));
    }

    @Test
    void rejectsInactiveCategoryAndUnitsBeforeCreatingProduct() {
        UUID activeCategory = category(true), inactiveCategory = category(false), activeUnit = unit(true), inactiveUnit = unit(false);
        String categorySku = "P-" + UUID.randomUUID();
        String unitSku = "P-" + UUID.randomUUID();
        assertThrows(ProductReferenceException.class,
                () -> products.create(command(categorySku, inactiveCategory, activeUnit, activeUnit, List.of())));
        assertThrows(ProductReferenceException.class,
                () -> products.create(command(unitSku, activeCategory, inactiveUnit, activeUnit,
                        List.of(new CreateProductCommand.Conversion(inactiveUnit, activeUnit, BigDecimal.ONE, true)))));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.product WHERE sku IN (?,?)", Integer.class, categorySku, unitSku));
    }

    @Test
    void enforcesUniqueSkuAndCanonicalFieldAndDecimalLimits() {
        UUID category = category(true), unit = unit(true);
        String sku = "P-" + UUID.randomUUID();
        products.create(command(sku, category, unit, unit, List.of()));
        assertThrows(DuplicateKeyException.class, () -> products.create(command(sku, category, unit, unit, List.of())));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.product WHERE sku=?", Integer.class, sku));

        assertThrows(ProductFieldValidationException.class, () -> products.create(command("x".repeat(81), category, unit, unit, List.of())));
        assertThrows(ProductFieldValidationException.class, () -> products.create(new CreateProductCommand("P-" + UUID.randomUUID(), "x".repeat(201), category,
                Product.Type.MATERIAL, unit, unit, BigDecimal.ZERO, false, false, false, false,
                JsonNodeFactory.instance.objectNode(), List.of(), UUID.randomUUID())));
        assertThrows(ProductFieldValidationException.class, () -> products.create(new CreateProductCommand("P-" + UUID.randomUUID(), "Producto", category,
                Product.Type.MATERIAL, unit, unit, BigDecimal.ZERO, false, false, false, false,
                JsonNodeFactory.instance.textNode("no es objeto"), List.of(), UUID.randomUUID())));
        assertThrows(ProductFieldValidationException.class, () -> products.create(new CreateProductCommand("P-" + UUID.randomUUID(), "Producto", category,
                Product.Type.MATERIAL, unit, unit, new BigDecimal("0.0000001"), false, false, false, false,
                JsonNodeFactory.instance.objectNode(), List.of(), UUID.randomUUID())));
        assertThrows(ProductFieldValidationException.class, () -> products.create(new CreateProductCommand("P-" + UUID.randomUUID(), "Producto", category,
                Product.Type.MATERIAL, unit, unit, new BigDecimal("1000000000000"), false, false, false, false,
                JsonNodeFactory.instance.objectNode(), List.of(), UUID.randomUUID())));
    }

    @Test
    void acceptsIndependentTraceabilityFlags() {
        UUID category = category(true), unit = unit(true);
        String sku = "P-" + UUID.randomUUID();
        skus.add(sku);
        products.create(new CreateProductCommand(sku, "Producto trazable", category, Product.Type.MATERIAL,
                unit, unit, BigDecimal.ZERO, false, true, false, true,
                JsonNodeFactory.instance.objectNode(), List.of(), UUID.randomUUID()));

        var flags = jdbc.queryForMap("SELECT requires_lot,requires_heat_number,requires_expiry,requires_serial FROM catalog.product WHERE sku=?", sku);
        assertEquals(false, flags.get("requires_lot"));
        assertEquals(true, flags.get("requires_heat_number"));
        assertEquals(false, flags.get("requires_expiry"));
        assertEquals(true, flags.get("requires_serial"));
    }

    @Test
    void rejectsInvalidConversionAndInactiveConversionUnitBeforePersistence() {
        UUID category = category(true), activeUnit = unit(true), inactiveUnit = unit(false);
        String inactiveSku = "P-" + UUID.randomUUID();
        String sameUnitSku = "P-" + UUID.randomUUID();
        String zeroFactorSku = "P-" + UUID.randomUUID();
        String precisionSku = "P-" + UUID.randomUUID();
        assertThrows(ProductReferenceException.class, () -> products.create(command(inactiveSku, category, activeUnit, activeUnit,
                List.of(new CreateProductCommand.Conversion(activeUnit, inactiveUnit, BigDecimal.ONE, true)))));
        assertThrows(ProductFieldValidationException.class, () -> products.create(command(sameUnitSku, category, activeUnit, activeUnit,
                List.of(new CreateProductCommand.Conversion(activeUnit, activeUnit, BigDecimal.ONE, true)))));
        assertThrows(ProductFieldValidationException.class, () -> products.create(command(zeroFactorSku, category, activeUnit, activeUnit,
                List.of(new CreateProductCommand.Conversion(activeUnit, UUID.randomUUID(), BigDecimal.ZERO, true)))));
        assertThrows(ProductFieldValidationException.class, () -> products.create(command(precisionSku, category, activeUnit, activeUnit,
                List.of(new CreateProductCommand.Conversion(activeUnit, UUID.randomUUID(), new BigDecimal("1.0000001"), true)))));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.product WHERE sku=?", Integer.class, inactiveSku));
    }

    @Test
    void postProductReturnsCanonicalResponseAndPendingEventWithResolvedCorrelation() throws Exception {
        UUID category = category(true), storage = unit(true), base = unit(true);
        String sku = "P-" + UUID.randomUUID(), correlation = UUID.randomUUID().toString();
        skus.add(sku);
        mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token("PRODUCT_WRITE"))
                        .header("X-Correlation-ID", correlation).contentType("application/json")
                        .content(json(sku, category, storage, base, "[{\"fromUnitId\":\"" + storage + "\",\"toUnitId\":\"" + base + "\",\"factor\":2.5}]")))
                .andExpect(status().isCreated()).andExpect(header().string("X-Correlation-ID", correlation))
                .andExpect(jsonPath("id").isString()).andExpect(jsonPath("sku").value(sku))
                .andExpect(jsonPath("minStock").value(12.345678)).andExpect(jsonPath("active").value(true))
                .andExpect(jsonPath("version").value(0)).andExpect(jsonPath("conversions[0].factor").value(2.5));
        assertEquals(correlation, jdbc.queryForObject("SELECT correlation_id::text FROM catalog.outbox_event o JOIN catalog.product p ON p.id=o.aggregate_id WHERE p.sku=?", String.class, sku));
    }

    @Test
    void postProductProtectsAndMapsReferenceAndConversionFailuresWithoutWrites() throws Exception {
        UUID category = category(true), storage = unit(true), base = unit(true);
        String sku = "P-" + UUID.randomUUID();
        String valid = json(sku, category, storage, base, "[{\"fromUnitId\":\"" + storage + "\",\"toUnitId\":\"" + base + "\",\"factor\":1}]");
        mvc.perform(post("/api/v1/products").contentType("application/json").content(valid)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token("INVENTORY_READ")).contentType("application/json").content(valid)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token("PRODUCT_WRITE")).contentType("application/json")
                        .content(json("P-" + UUID.randomUUID(), UUID.randomUUID(), storage, base, "[{\"fromUnitId\":\"" + storage + "\",\"toUnitId\":\"" + base + "\",\"factor\":1}]")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("CATEGORY_NOT_FOUND")).andExpect(jsonPath("field").value("categoryId"));
        UUID inactiveCategory = category(false);
        mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token("PRODUCT_WRITE")).contentType("application/json")
                        .content(json("P-" + UUID.randomUUID(), inactiveCategory, storage, base, "[{\"fromUnitId\":\"" + storage + "\",\"toUnitId\":\"" + base + "\",\"factor\":1}]")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CATEGORY_INACTIVE")).andExpect(jsonPath("field").value("categoryId"));
        mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token("PRODUCT_WRITE")).contentType("application/json")
                        .content(json("P-" + UUID.randomUUID(), category, storage, base, "[]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.product WHERE sku=?", Integer.class, sku));
    }
}
