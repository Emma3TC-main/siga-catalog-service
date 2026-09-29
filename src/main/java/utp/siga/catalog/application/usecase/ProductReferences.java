package utp.siga.catalog.application.usecase;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductReferenceException;

/** Shared reference rules for POST and PUT; no second set of product invariants. */
final class ProductReferences {
    private final CategoryStore categories;
    private final UnitOfMeasureStore units;

    ProductReferences(CategoryStore categories, UnitOfMeasureStore units) {
        this.categories = categories;
        this.units = units;
    }

    Map<UUID, String> validate(Product product) {
        if (!categories.exists(product.categoryId()))
            throw new ProductReferenceException("categoryId", true);
        if (!categories.isActive(product.categoryId()))
            throw new ProductReferenceException("categoryId", false);
        var unitIds = new LinkedHashSet<UUID>();
        unitIds.add(product.storageUnitId());
        unitIds.add(product.baseUnitId());
        product.conversions().forEach(conversion -> {
            unitIds.add(conversion.fromUnitId());
            unitIds.add(conversion.toUnitId());
        });
        var codes = new LinkedHashMap<UUID, String>();
        for (UUID id : unitIds) {
            String field = id.equals(product.storageUnitId()) ? "storageUnitId"
                    : id.equals(product.baseUnitId()) ? "baseUnitId" : "conversions.unitId";
            if (!units.exists(id)) throw new ProductReferenceException(field, true);
            if (!units.isActive(id)) throw new ProductReferenceException(field, false);
            codes.put(id, units.code(id));
        }
        return codes;
    }
}
