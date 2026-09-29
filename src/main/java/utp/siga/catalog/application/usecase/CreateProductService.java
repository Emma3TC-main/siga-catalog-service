package utp.siga.catalog.application.usecase;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductReferenceException;

public class CreateProductService implements CreateProductUseCase {
    private final ProductStore products;
    private final CategoryStore categories;
    private final UnitOfMeasureStore units;

    public CreateProductService(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        this.products = products;
        this.categories = categories;
        this.units = units;
    }

    @Override
    @Transactional
    public CreateProductUseCase.CreatedProduct create(CreateProductCommand command) {
        Product product = command.asDomain();
        validateCategory(product.categoryId());
        var unitIds = new LinkedHashSet<UUID>();
        unitIds.add(product.storageUnitId());
        unitIds.add(product.baseUnitId());
        product.conversions().forEach(conversion -> {
            unitIds.add(conversion.fromUnitId());
            unitIds.add(conversion.toUnitId());
        });
        var codes = new LinkedHashMap<UUID, String>();
        for (UUID unitId : unitIds) {
            validateUnit(unitId, unitField(product, unitId));
            codes.put(unitId, units.code(unitId));
        }
        UUID id = products.insert(product, command.correlationId(), codes);
        return new CreateProductUseCase.CreatedProduct(id, true, 0L, product);
    }

    private void validateCategory(UUID id) {
        if (!categories.exists(id)) throw new ProductReferenceException("categoryId", true);
        if (!categories.isActive(id)) throw new ProductReferenceException("categoryId", false);
    }
    private void validateUnit(UUID id, String field) {
        if (!units.exists(id)) throw new ProductReferenceException(field, true);
        if (!units.isActive(id)) throw new ProductReferenceException(field, false);
    }
    private String unitField(Product product, UUID id) {
        if (id.equals(product.storageUnitId())) return "storageUnitId";
        if (id.equals(product.baseUnitId())) return "baseUnitId";
        return "conversions.unitId";
    }
}
