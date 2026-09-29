package utp.siga.catalog.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.UnitOfMeasure;
import utp.siga.catalog.domain.model.UnitOfMeasureSummary;

@Repository
public class PostgresUnitOfMeasureStore implements UnitOfMeasureStore {
    private final JdbcTemplate jdbc;

    public PostgresUnitOfMeasureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(UnitOfMeasure unit) {
        jdbc.update("INSERT INTO catalog.unit_measure(code,name,symbol,dimension) VALUES (?,?,?,?)",
                unit.code(), unit.name(), unit.symbol(), unit.dimension());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<UnitOfMeasureSummary> findAllOrderByCode() {
        return jdbc.query("SELECT id,code,name,symbol,dimension,active FROM catalog.unit_measure ORDER BY code ASC",
                (row, index) -> new UnitOfMeasureSummary(row.getObject("id", java.util.UUID.class), row.getString("code"),
                        row.getString("name"), row.getString("symbol"), row.getString("dimension"), row.getBoolean("active")));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(java.util.UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM catalog.unit_measure WHERE id=? AND active=true)", Boolean.class, id));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(java.util.UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM catalog.unit_measure WHERE id=?)", Boolean.class, id));
    }

    @Override
    @Transactional(readOnly = true)
    public String code(java.util.UUID id) {
        return jdbc.queryForObject("SELECT code FROM catalog.unit_measure WHERE id=?", String.class, id);
    }
}
