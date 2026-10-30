package utp.siga.catalog.application.usecase;

import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.GetProductUseCase;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.domain.model.ProductDetails;
import utp.siga.catalog.domain.model.ProductNotFoundException;

public class GetProductService implements GetProductUseCase {
    private final ProductStore products;
    public GetProductService(ProductStore products) { this.products = products; }
    @Override @Transactional(readOnly = true)
    public ProductDetails get(UUID id) { return products.findById(id).orElseThrow(ProductNotFoundException::new); }
}
