package utp.siga.catalog.application.usecase;

import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.Product;

public class CreateProductService implements CreateProductUseCase {
    private final ProductStore products;
    private final ProductReferences references;

    public CreateProductService(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        this.products = products;
        this.references = new ProductReferences(categories, units);
    }

    @Override
    @Transactional
    public CreateProductUseCase.CreatedProduct create(CreateProductCommand command) {
        Product product = command.asDomain();
        var codes = references.validate(product);
        UUID id = products.insert(product, command.correlationId(), codes);
        return new CreateProductUseCase.CreatedProduct(id, true, 0L, product);
    }

}
