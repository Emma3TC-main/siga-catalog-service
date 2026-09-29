package utp.siga.catalog.application.usecase;

import java.util.List;
import utp.siga.catalog.application.port.in.ListCategoriesUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.domain.model.CategorySummary;

public class ListCategoriesService implements ListCategoriesUseCase {
    private final CategoryStore store;

    public ListCategoriesService(CategoryStore store) { this.store = store; }

    @Override
    public List<CategorySummary> list() { return store.findAllOrderByCode(); }
}
