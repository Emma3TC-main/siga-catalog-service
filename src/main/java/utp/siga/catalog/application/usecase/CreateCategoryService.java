package utp.siga.catalog.application.usecase;
import utp.siga.catalog.application.port.in.CreateCategoryUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.domain.model.Category;
public class CreateCategoryService implements CreateCategoryUseCase {
    private final CategoryStore store;
    public CreateCategoryService(CategoryStore store) { this.store = store; }
    public void create(Category category) { store.insert(category); }
}
