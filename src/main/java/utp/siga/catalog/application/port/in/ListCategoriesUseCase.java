package utp.siga.catalog.application.port.in;

import java.util.List;
import utp.siga.catalog.domain.model.CategorySummary;

public interface ListCategoriesUseCase {
    List<CategorySummary> list();
}
