package utp.siga.catalog.application.port.out;
import java.util.List;
import java.util.UUID;
import utp.siga.catalog.domain.model.Category;
import utp.siga.catalog.domain.model.CategorySummary;
public interface CategoryStore {
    void insert(Category category);
    List<CategorySummary> findAllOrderByCode();
    boolean isActive(UUID id);
    boolean exists(UUID id);
}
