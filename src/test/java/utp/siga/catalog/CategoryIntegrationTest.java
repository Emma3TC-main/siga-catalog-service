package utp.siga.catalog;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class CategoryIntegrationTest {
    static RSAKey key;
    static HttpServer jwks;
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-only").generate();
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            jwks.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            jwks.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.security.jwks-uri",() -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void shutdown() { jwks.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final List<String> created = new ArrayList<>();
    String code() { String code="IT-"+UUID.randomUUID(); created.add(code); return code; }
    @AfterEach void cleanup() { for (String code:created) jdbc.update("DELETE FROM catalog.category WHERE code=?",code); }
    String token(String permission,String issuer,String audience,Instant expiration,RSAKey signingKey) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expiration)).claim("permissions",List.of(permission)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-only").build(),claims);
        jwt.sign(new RSASSASigner(signingKey)); return jwt.serialize();
    }
    String token(String permission) throws Exception { return token(permission,"siga-identity","siga-api",Instant.now().plusSeconds(300),key); }
    String body(String code) { return "{\"code\":\""+code+"\",\"name\":\"Categoría prueba\",\"categoryType\":\"MATERIAL\"}"; }
    int create(String code,String token) throws Exception {
        return mvc.perform(post("/api/v1/categories").header("Authorization","Bearer "+token)
                .contentType("application/json").content(body(code))).andReturn().getResponse().getStatus();
    }
    @Test void createsPersistedCategoryAndEmptyCanonicalResponse() throws Exception {
        String code=code(); String correlation=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/categories").header("Authorization","Bearer "+token("PRODUCT_WRITE"))
            .header("X-Correlation-ID",correlation).contentType("application/json").content(body(code)))
            .andExpect(status().isCreated()).andExpect(content().string("")).andExpect(header().string("X-Correlation-ID",correlation));
        var row=jdbc.queryForMap("SELECT active,version,category_type FROM catalog.category WHERE code=?",code);
        assertEquals(true,row.get("active")); assertEquals(0L,row.get("version")); assertEquals("MATERIAL",row.get("category_type"));
    }
    @Test void rejectsDuplicateWithoutSecondRow() throws Exception {
        String code=code(), token=token("PRODUCT_WRITE"); assertEquals(201,create(code,token));
        mvc.perform(post("/api/v1/categories").header("Authorization","Bearer "+token).contentType("application/json").content(body(code)))
            .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("code").value("DATA_CONFLICT"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM catalog.category WHERE code=?",Integer.class,code));
    }
    @Test void concurrentDuplicateIsAtomic() throws Exception {
        String code=code(),token=token("PRODUCT_WRITE");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Integer> call=() -> { gate.await(); return create(code,token); };
            var a=executor.submit(call); var b=executor.submit(call); gate.countDown();
            assertEquals(Set.of(201,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
        }
    }
    @Test void validatesRequiredFieldsEnumAndPhysicalLimits() throws Exception {
        for(String body:List.of("{}", "{\"code\":\"X\",\"name\":\"X\",\"categoryType\":\"OTHER\"}",body("x".repeat(51))))
            mvc.perform(post("/api/v1/categories").header("Authorization","Bearer "+token("PRODUCT_WRITE")).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("detail").value("Revisa los campos de la categoría"));
    }
    @Test void deniesUnauthenticatedAndInsufficientPermissionWithoutWriting() throws Exception {
        String code=code();
        mvc.perform(post("/api/v1/categories").contentType("application/json").content(body(code))).andExpect(status().isUnauthorized());
        assertEquals(403,create(code,token("INVENTORY_READ")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM catalog.category WHERE code=?",Integer.class,code));
    }
    @Test void rejectsWrongIssuerAudienceExpiredAndBadSignature() throws Exception {
        String code=code(); Instant future=Instant.now().plusSeconds(300);
        for(String jwt:List.of(token("PRODUCT_WRITE","wrong","siga-api",future,key),
                token("PRODUCT_WRITE","siga-identity","wrong",future,key),
                token("PRODUCT_WRITE","siga-identity","siga-api",Instant.now().minusSeconds(120),key),
                token("PRODUCT_WRITE","siga-identity","siga-api",future,new RSAKeyGenerator(2048).generate())))
            assertEquals(401,create(code,jwt));
    }
    @Test void listsCategoriesAsOrderedDirectArrayIncludingInactive() throws Exception {
        String first = "A-" + UUID.randomUUID(), last = "Z-" + UUID.randomUUID();
        created.add(first); created.add(last);
        jdbc.update("INSERT INTO catalog.category(code,name,category_type,active) VALUES (?,?,?,?)", first, "Activa", "MATERIAL", true);
        jdbc.update("INSERT INTO catalog.category(code,name,category_type,active) VALUES (?,?,?,?)", last, "Inactiva", "REPUESTO", false);
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token("PRODUCT_WRITE")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray()).andReturn();
        var items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        var codes = new ArrayList<String>();
        boolean inactiveFound = false;
        for (var item : items) {
            var fields = new HashSet<String>();
            item.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "code", "name", "categoryType", "active"), fields);
            codes.add(item.get("code").asText());
            if (last.equals(item.get("code").asText()) && !item.get("active").asBoolean()) inactiveFound = true;
        }
        assertEquals(codes.stream().sorted().toList(), codes);
        assertEquals(true, inactiveFound);
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void listsEmptyCategories() throws Exception {
        jdbc.update("DELETE FROM catalog.unit_conversion");
        jdbc.update("DELETE FROM catalog.product");
        jdbc.update("DELETE FROM catalog.category");
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token("PRODUCT_WRITE")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void protectsCategoryList() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token("INVENTORY_READ"))).andExpect(status().isForbidden());
    }
    @Test void databaseOwnershipAndIsolation() {
        assertEquals("siga_catalog_test",jdbc.queryForObject("SELECT current_user",String.class));
        assertEquals("siga_catalog_local_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals(7,jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='catalog'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='iam'",Integer.class));
        assertEquals(false,jdbc.queryForObject("SELECT has_database_privilege(current_user,'siga','CONNECT')",Boolean.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM catalog.flyway_schema_history WHERE success",Integer.class));
    }
}
