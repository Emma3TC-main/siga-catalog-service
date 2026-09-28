package utp.siga.catalog.application.usecase;

import java.util.List;
import utp.siga.catalog.application.port.in.ListUnitsOfMeasureUseCase;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.UnitOfMeasureSummary;

public class ListUnitsOfMeasureService implements ListUnitsOfMeasureUseCase {
    private final UnitOfMeasureStore store;

    public ListUnitsOfMeasureService(UnitOfMeasureStore store) { this.store = store; }

    @Override
    public List<UnitOfMeasureSummary> list() { return store.findAllOrderByCode(); }
}
