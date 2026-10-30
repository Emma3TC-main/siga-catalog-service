package utp.siga.catalog.application.usecase;

import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.application.port.out.ProductStore;

public class ListProductsService implements ListProductsUseCase {
    private final ProductStore products;
    public ListProductsService(ProductStore products) { this.products = products; }
    @Override @Transactional(readOnly = true)
    public Page list(Query query) { return products.findPage(query); }
}
