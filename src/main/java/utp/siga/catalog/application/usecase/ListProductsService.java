package utp.siga.catalog.application.usecase;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.domain.model.ProductSnapshot;

public class ListProductsService implements ListProductsUseCase {
    private final ProductStore products;

    public ListProductsService(ProductStore products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSnapshot> list() {
        return products.findAllOrderBySku();
    }
}
