package utp.siga.catalog.application.port.out;

import java.util.List;
import java.util.UUID;
import utp.siga.catalog.domain.model.UnitOfMeasure;
import utp.siga.catalog.domain.model.UnitOfMeasureSummary;

public interface UnitOfMeasureStore {
    void insert(UnitOfMeasure unit);
    List<UnitOfMeasureSummary> findAllOrderByCode();
    boolean isActive(UUID id);
    boolean exists(UUID id);
    String code(UUID id);
}
