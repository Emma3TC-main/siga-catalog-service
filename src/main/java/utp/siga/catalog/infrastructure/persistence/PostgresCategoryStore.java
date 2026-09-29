package utp.siga.catalog.infrastructure.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.domain.model.Category;
import utp.siga.catalog.domain.model.CategorySummary;
@Repository
public class PostgresCategoryStore implements CategoryStore {
    private final JdbcTemplate jdbc;
    public PostgresCategoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void insert(Category category) {
        jdbc.update("INSERT INTO catalog.category(code,name,category_type) VALUES (?,?,?)",
                category.code(), category.name(), category.categoryType().name());
    }
    @Override
    @Transactional(readOnly = true)
    public java.util.List<CategorySummary> findAllOrderByCode() {
        return jdbc.query("SELECT id,code,name,category_type,active FROM catalog.category ORDER BY code ASC",
                (row, index) -> new CategorySummary(row.getObject("id", java.util.UUID.class), row.getString("code"),
                        row.getString("name"), Category.Type.valueOf(row.getString("category_type")), row.getBoolean("active")));
    }
    @Override
    @Transactional(readOnly = true)
    public boolean isActive(java.util.UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM catalog.category WHERE id=? AND active=true)", Boolean.class, id));
    }
    @Override
    @Transactional(readOnly = true)
    public boolean exists(java.util.UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM catalog.category WHERE id=?)", Boolean.class, id));
    }
}
