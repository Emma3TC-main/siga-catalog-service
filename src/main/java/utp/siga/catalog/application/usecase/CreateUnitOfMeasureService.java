package utp.siga.catalog.application.usecase;

import utp.siga.catalog.application.port.in.CreateUnitOfMeasureUseCase;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.UnitOfMeasure;

public class CreateUnitOfMeasureService implements CreateUnitOfMeasureUseCase {
    private final UnitOfMeasureStore store;

    public CreateUnitOfMeasureService(UnitOfMeasureStore store) {
        this.store = store;
    }

    @Override
    public void create(UnitOfMeasure unit) {
        store.insert(unit);
    }
}
