package utp.siga.catalog;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SupplierIntegrationTest {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("supplier-test-only").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        registry.add("catalog.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void shutdown() {
        JWKS.stop(0);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    private final List<UUID> suppliers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID id : suppliers) {
            jdbc.update("DELETE FROM catalog.outbox_event WHERE aggregate_id=?", id);
            jdbc.update("DELETE FROM catalog.supplier WHERE id=?", id);
        }
    }

    @Test
    void postValidatesFieldsAndPersistsSupplierAndCreatedEventAtomically() throws Exception {
        String code = code("POST");
        String taxId = taxId();
        int suppliersBefore = count("SELECT count(*) FROM catalog.supplier");
        int eventsBefore = count("SELECT count(*) FROM catalog.outbox_event");

        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE"))
                        .header("X-Correlation-ID", UUID.randomUUID().toString()).contentType("application/json")
                        .content(body(code, taxId, "Proveedor POST", "contacto@example.test", null, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("code").value(code))
                .andExpect(jsonPath("active").value(true)).andExpect(jsonPath("version").value(0));

        UUID id = supplierId(code);
        assertEquals(suppliersBefore + 1, count("SELECT count(*) FROM catalog.supplier"));
        assertEquals(eventsBefore + 1, count("SELECT count(*) FROM catalog.outbox_event"));
        assertEquals(1, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "' AND aggregate_type='Supplier' AND event_type='SupplierCreated' AND status='PENDING'"));
        assertEquals(code, jdbc.queryForObject("SELECT payload->'supplier'->>'code' FROM catalog.outbox_event WHERE aggregate_id=?", String.class, id));
        assertEquals("0", jdbc.queryForObject("SELECT payload->'supplier'->>'version' FROM catalog.outbox_event WHERE aggregate_id=?", String.class, id));

        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code("INACTIVE"), taxId(), "No activo", null, "false", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body("x".repeat(51), taxId(), "Largo", null, null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code("TAX-LONG"), "x".repeat(21), "Largo", null, null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code("NAME-LONG"), taxId(), "x".repeat(201), null, null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code("EMAIL"), taxId(), "Email", "invalido", null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code, taxId(), "Código duplicado", null, null, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DATA_CONFLICT"));
        mvc.perform(post("/api/v1/suppliers").header("Authorization", bearer("SUPPLIER_MANAGE")).contentType("application/json")
                        .content(body(code("TAX"), taxId, "RUC duplicado", null, null, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DATA_CONFLICT"));

        assertEquals(1, count("SELECT count(*) FROM catalog.supplier WHERE code='" + code + "'"));
        assertEquals(1, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'"));
        assertEquals(eventsBefore + 1, count("SELECT count(*) FROM catalog.outbox_event"));
    }

    @Test
    void listsSuppliersWithLiteralSearchFiltersOrderingPaginationInactiveAndEmptyPage() throws Exception {
        UUID literal = insert("S%_A" + suffix(), taxId(), "Mismo nombre", true);
        UUID other = insert("S-" + suffix(), taxId(), "Mismo nombre", true);
        UUID inactive = insert("S%_I" + suffix(), taxId(), "Inactivo", false);
        String bearer = bearer("SUPPLIER_MANAGE");

        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("q", "%_").param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("content.length()").value(1))
                .andExpect(jsonPath("content[0].id").value(literal.toString()));
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("content[?(@.id == '" + inactive + "')]").isNotEmpty());
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("q", "Mismo nombre").param("sort", "businessName,asc").param("size", "1").param("page", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("content.length()").value(1)).andExpect(jsonPath("page").value(0))
                .andExpect(jsonPath("size").value(1)).andExpect(jsonPath("totalElements").value(2));
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("q", "sin-coincidencias-" + suffix()))
                .andExpect(status().isOk()).andExpect(jsonPath("content.length()").value(0)).andExpect(jsonPath("totalElements").value(0));
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer).param("sort", "taxId,asc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        assertEquals(other, supplierId(jdbc.queryForObject("SELECT code FROM catalog.supplier WHERE id=?", String.class, other)));
    }

    @Test
    void getsSuppliersWithAuthorizationWithoutWritingData() throws Exception {
        UUID id = insert(code("GET"), taxId(), "Proveedor lectura", true);
        long version = jdbc.queryForObject("SELECT version FROM catalog.supplier WHERE id=?", Long.class, id);
        int events = count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'");

        mvc.perform(get("/api/v1/suppliers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer("PRODUCT_WRITE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/suppliers/{id}", id).header("Authorization", bearer("CATALOG_READ")))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(id.toString()));
        mvc.perform(get("/api/v1/suppliers/{id}", UUID.randomUUID()).header("Authorization", bearer("SUPPLIER_MANAGE")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SUPPLIER_NOT_FOUND"));
        assertEquals(version, jdbc.queryForObject("SELECT version FROM catalog.supplier WHERE id=?", Long.class, id));
        assertEquals(events, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'"));
    }

    @Test
    void updatesWithOptimisticVersioningNoOpAndStateTransitionsInSameTransaction() throws Exception {
        String code = code("PUT");
        UUID id = insert(code, taxId(), "Proveedor inicial", true);
        String bearer = bearer("SUPPLIER_MANAGE");
        int eventsBefore = count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'");
        String update = body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Proveedor actualizado", "actualizado@example.test", "true", "0");

        mvc.perform(put("/api/v1/suppliers/{id}", id).contentType("application/json").content(update))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer("PRODUCT_WRITE")).contentType("application/json").content(update))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        assertEquals(eventsBefore + 1, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "' AND event_type='SupplierUpdated'"));

        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Versión vieja", "actualizado@example.test", "true", "0")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("VERSION_CONFLICT"));
        int afterChanged = count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'");
        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Proveedor actualizado", "actualizado@example.test", "true", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM catalog.supplier WHERE id=?", Long.class, id));
        assertEquals(afterChanged, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "'"));

        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Proveedor actualizado", "actualizado@example.test", "false", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("active").value(false)).andExpect(jsonPath("version").value(2));
        assertEquals(1, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "' AND event_type='SupplierDisabled'"));
        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Proveedor actualizado", "actualizado@example.test", "true", "2")))
                .andExpect(status().isOk()).andExpect(jsonPath("active").value(true)).andExpect(jsonPath("version").value(3));
        assertEquals(2, count("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id='" + id + "' AND event_type='SupplierUpdated'"));

        mvc.perform(put("/api/v1/suppliers/{id}", UUID.randomUUID()).header("Authorization", bearer).contentType("application/json")
                        .content(body(code("MISSING"), taxId(), "No existe", null, "true", "0")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SUPPLIER_NOT_FOUND"));
        mvc.perform(put("/api/v1/suppliers/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content(body(code, jdbc.queryForObject("SELECT tax_id FROM catalog.supplier WHERE id=?", String.class, id), "Proveedor actualizado", null, "true", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
    }

    private UUID insert(String code, String taxId, String name, boolean active) {
        UUID id = UUID.randomUUID();
        suppliers.add(id);
        jdbc.update("INSERT INTO catalog.supplier(id,code,tax_id,business_name,active) VALUES(?,?,?,?,?)", id, code, taxId, name, active);
        return id;
    }

    private UUID supplierId(String code) {
        UUID id = jdbc.queryForObject("SELECT id FROM catalog.supplier WHERE code=?", UUID.class, code);
        suppliers.add(id);
        return id;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String body(String code, String taxId, String businessName, String email, String active, String expectedVersion) {
        String result = "{\"code\":\"" + code + "\",\"taxId\":\"" + taxId + "\",\"businessName\":\"" + businessName + "\"";
        if (email != null) result += ",\"email\":\"" + email + "\"";
        if (active != null) result += ",\"active\":" + active;
        if (expectedVersion != null) result += ",\"expectedVersion\":" + expectedVersion;
        return result + "}";
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String code(String prefix) {
        return prefix + "-" + suffix();
    }

    private static String taxId() {
        return "T-" + suffix();
    }

    private static String bearer(String permission) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("siga-identity").audience("siga-api").subject(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("permissions", List.of(permission)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("supplier-test-only").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return "Bearer " + jwt.serialize();
    }
}
