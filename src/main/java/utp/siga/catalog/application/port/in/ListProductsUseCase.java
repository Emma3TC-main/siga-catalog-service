package utp.siga.catalog.application.port.in;

import java.util.List;
import utp.siga.catalog.domain.model.ProductSnapshot;

public interface ListProductsUseCase {
    List<ProductSnapshot> list();
}
