package utp.siga.catalog.application.usecase;

import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.UpdateProductCommand;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.ProductNotFoundException;
import utp.siga.catalog.domain.model.ProductSnapshot;

public class UpdateProductService implements UpdateProductUseCase {
    private final ProductStore products;
    private final ProductReferences references;

    public UpdateProductService(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        this.products = products;
        this.references = new ProductReferences(categories, units);
    }

    @Override
    @Transactional
    public ProductSnapshot update(UpdateProductCommand command) {
        if (!products.lock(command.id())) throw new ProductNotFoundException();
        var unitCodes = references.validate(command.product());
        return products.update(command.id(), command.product(), command.correlationId(), unitCodes);
    }
}
