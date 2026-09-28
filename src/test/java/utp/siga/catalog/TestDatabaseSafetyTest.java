package utp.siga.catalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class TestDatabaseSafetyTest {
    MockEnvironment safe() { return new MockEnvironment()
            .withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:15432/siga_catalog_local_test")
            .withProperty("spring.datasource.username","siga_catalog_test")
            .withProperty("spring.flyway.schemas","catalog").withProperty("spring.flyway.default-schema","catalog"); }
    @Test void allowsDedicatedDatabase() { assertDoesNotThrow(() -> TestDatabaseSafety.validate(safe())); }
    @Test void rejectsSharedDatabaseAndParameters() {
        for (String url : new String[]{"jdbc:postgresql://127.0.0.1:15432/siga", "jdbc:postgresql://127.0.0.1:15432/siga_catalog_local_test?user=postgres"})
            assertThrows(IllegalStateException.class,() -> TestDatabaseSafety.validate(safe().withProperty("spring.datasource.url",url)));
    }
    @Test void rejectsAdminAndAlternateConnections() {
        assertThrows(IllegalStateException.class,() -> TestDatabaseSafety.validate(safe().withProperty("spring.datasource.username","postgres")));
        for (String key : new String[]{"spring.flyway.url","spring.datasource.hikari.jdbc-url","spring.datasource.jndi-name"})
            assertThrows(IllegalStateException.class,() -> TestDatabaseSafety.validate(safe().withProperty(key,"anything")));
    }
    @Test void guardRunsBeforeSingletonsCanConnect() {
        var connected = new java.util.concurrent.atomic.AtomicBoolean();
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.setEnvironment(safe().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:15432/siga"));
            context.registerBean("connectionSentinel",Object.class,() -> { connected.set(true); return new Object(); });
            new TestDatabaseSafety().initialize(context);
            assertThrows(IllegalStateException.class,context::refresh);
            assertFalse(connected.get());
        }
    }
}
