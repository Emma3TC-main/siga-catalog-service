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
import java.util.Date;
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UnitIntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("unit-test-only").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
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
    static void shutdown() { JWKS.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final List<String> createdCodes = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String code : createdCodes) jdbc.update("DELETE FROM catalog.unit_measure WHERE code=?", code);
    }

    private String token(String permission) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("siga-identity").audience("siga-api")
                .subject(UUID.randomUUID().toString()).issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("permissions", List.of(permission)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unit-test-only").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private String code() {
        String code = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createdCodes.add(code);
        return code;
    }
    private String body(String code) { return "{\"code\":\"" + code + "\",\"name\":\"Unidad prueba\",\"symbol\":\"up\",\"dimension\":\"TEST\"}"; }

    @Test
    void createsUnitWithEmptyContractualResponse() throws Exception {
        String createdCode = code();
        mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + token("PRODUCT_WRITE"))
                        .contentType("application/json").content(body(createdCode)))
                .andExpect(status().isCreated()).andExpect(content().string(""));
        var row = jdbc.queryForMap("SELECT active,version,name,symbol,dimension FROM catalog.unit_measure WHERE code=?", createdCode);
        assertEquals(true, row.get("active"));
        assertEquals(0L, row.get("version"));
        assertEquals("Unidad prueba", row.get("name"));
        assertEquals("up", row.get("symbol"));
        assertEquals("TEST", row.get("dimension"));
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        String createdCode = code();
        String access = token("PRODUCT_WRITE");
        mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + access).contentType("application/json").content(body(createdCode))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + access).contentType("application/json").content(body(createdCode)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DATA_CONFLICT"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.unit_measure WHERE code=?", Integer.class, createdCode));
    }

    @Test
    void identifiesInvalidUnitFieldForRequiredFieldsAndPhysicalLengths() throws Exception {
        String access = token("PRODUCT_WRITE");
        var invalid = java.util.Map.of(
                body("x".repeat(21)), "Revisa el campo code de la unidad",
                "{\"code\":\"U\",\"name\":\"" + "x".repeat(81) + "\",\"symbol\":\"u\",\"dimension\":\"TEST\"}", "Revisa el campo name de la unidad",
                "{\"code\":\"U\",\"name\":\"U\",\"symbol\":\"" + "x".repeat(21) + "\",\"dimension\":\"TEST\"}", "Revisa el campo symbol de la unidad",
                "{\"code\":\"U\",\"name\":\"U\",\"symbol\":\"u\",\"dimension\":\"" + "x".repeat(41) + "\"}", "Revisa el campo dimension de la unidad");
        for (var invalidRequest : invalid.entrySet()) {
            mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + access).contentType("application/json").content(invalidRequest.getKey()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("detail").value(invalidRequest.getValue()));
        }
        mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + access).contentType("application/json")
                        .content("{\"name\":\"U\",\"symbol\":\"u\",\"dimension\":\"TEST\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Revisa el campo code de la unidad"));
    }

    @Test
    void rejectsUnauthenticatedAndInsufficientPermissionWithoutWriting() throws Exception {
        String createdCode = code();
        mvc.perform(post("/api/v1/units").contentType("application/json").content(body(createdCode))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/units").header("Authorization", "Bearer " + token("INVENTORY_READ"))
                        .contentType("application/json").content(body(createdCode))).andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.unit_measure WHERE code=?", Integer.class, createdCode));
    }
    @Test
    void listsUnitsAsOrderedDirectArrayIncludingInactive() throws Exception {
        String first = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String last = "Z" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createdCodes.add(first); createdCodes.add(last);
        jdbc.update("INSERT INTO catalog.unit_measure(code,name,symbol,dimension,active) VALUES (?,?,?,?,?)", first, "Activa", "a", "TEST", true);
        jdbc.update("INSERT INTO catalog.unit_measure(code,name,symbol,dimension,active) VALUES (?,?,?,?,?)", last, "Inactiva", "z", "TEST", false);
        var result = mvc.perform(get("/api/v1/units").header("Authorization", "Bearer " + token("PRODUCT_WRITE")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray()).andReturn();
        var items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        var codes = new ArrayList<String>();
        boolean inactiveFound = false;
        for (var item : items) {
            var fields = new java.util.HashSet<String>();
            item.fieldNames().forEachRemaining(fields::add);
            assertEquals(java.util.Set.of("id", "code", "name", "symbol", "dimension", "active"), fields);
            codes.add(item.get("code").asText());
            if (last.equals(item.get("code").asText()) && !item.get("active").asBoolean()) inactiveFound = true;
        }
        assertEquals(codes.stream().sorted().toList(), codes);
        assertEquals(true, inactiveFound);
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void listsEmptyUnits() throws Exception {
        jdbc.update("DELETE FROM catalog.unit_conversion");
        jdbc.update("DELETE FROM catalog.product");
        jdbc.update("DELETE FROM catalog.unit_measure");
        mvc.perform(get("/api/v1/units").header("Authorization", "Bearer " + token("PRODUCT_WRITE")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test
    void protectsUnitList() throws Exception {
        mvc.perform(get("/api/v1/units")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/units").header("Authorization", "Bearer " + token("INVENTORY_READ"))).andExpect(status().isForbidden());
    }
}
