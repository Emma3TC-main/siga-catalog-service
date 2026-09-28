package utp.siga.catalog;
import org.springframework.context.*;
import org.springframework.core.env.Environment;
public class TestDatabaseSafety implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(factory -> validate(context.getEnvironment()));
    }
    static void validate(Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/siga_catalog_local_test")
                || !"siga_catalog_test".equals(env.getProperty("spring.datasource.username")))
            throw new IllegalStateException("TEST_DATABASE_GUARD: dedicated Catalog test database/login required");
        for (String key : new String[]{"spring.flyway.url","spring.flyway.user","spring.flyway.password",
                "spring.datasource.jndi-name","spring.datasource.hikari.jdbc-url","spring.datasource.hikari.username",
                "spring.datasource.hikari.password","spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-properties.url","spring.datasource.hikari.data-source-properties.user",
                "spring.datasource.hikari.data-source-properties.databaseName","spring.datasource.type"})
            if (env.containsProperty(key)) throw new IllegalStateException("TEST_DATABASE_GUARD: alternate connection forbidden: " + key);
        if (!"catalog".equals(env.getProperty("spring.flyway.schemas"))
                || !"catalog".equals(env.getProperty("spring.flyway.default-schema")))
            throw new IllegalStateException("TEST_DATABASE_GUARD: Catalog schema required");
    }
}
