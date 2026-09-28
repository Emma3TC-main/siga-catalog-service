package utp.siga.catalog.application.port.out;
import java.util.List;
import utp.siga.catalog.domain.model.Category;
import utp.siga.catalog.domain.model.CategorySummary;
public interface CategoryStore {
    void insert(Category category);
    List<CategorySummary> findAllOrderByCode();
}
