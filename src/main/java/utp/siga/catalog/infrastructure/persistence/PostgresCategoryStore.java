package utp.siga.catalog.infrastructure.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.domain.model.Category;
@Repository
public class PostgresCategoryStore implements CategoryStore {
    private final JdbcTemplate jdbc;
    public PostgresCategoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void insert(Category category) {
        jdbc.update("INSERT INTO catalog.category(code,name,category_type) VALUES (?,?,?)",
                category.code(), category.name(), category.categoryType().name());
    }
}
