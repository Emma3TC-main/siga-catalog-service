package utp.siga.catalog.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.catalog.application.port.in.UpdateProductCommand;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductDetails;
import utp.siga.catalog.domain.model.ProductFieldValidationException;
import utp.siga.catalog.domain.model.ProductNotFoundException;
import utp.siga.catalog.domain.model.ProductReferenceException;
import utp.siga.catalog.domain.model.ProductVersionConflictException;
import utp.siga.catalog.domain.model.UnitConversion;

public class UpdateProductService implements UpdateProductUseCase {
    private final ProductStore products;
    private final CategoryStore categories;
    private final UnitOfMeasureStore units;

    public UpdateProductService(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        this.products = products;
        this.categories = categories;
        this.units = units;
    }

    @Override
    @Transactional
    public ProductDetails update(UUID id, UpdateProductCommand command) {
        ProductDetails current = products.findByIdForUpdate(id).orElseThrow(ProductNotFoundException::new);
        if (command.expectedVersion() == null || command.expectedVersion() < 0)
            throw new ProductFieldValidationException("expectedVersion");
        if (current.version() != command.expectedVersion())
            throw new ProductVersionConflictException();
        if (command.active() == null)
            throw new ProductFieldValidationException("active");

        Product desired = command.asDomain();
        boolean active = command.active();
        if (same(current, desired, active))
            return current;

        validateChangedReferences(current.product(), desired, active);
        var category = categories.findById(desired.categoryId())
                .orElseThrow(() -> new ProductReferenceException("categoryId", true));
        Map<UUID, String> unitCodes = unitCodes(desired);
        return products.update(id, desired, active, command.expectedVersion(), command.correlationId(), unitCodes,
                category);
    }

    private void validateChangedReferences(Product current, Product desired, boolean active) {
        if (active || !current.categoryId().equals(desired.categoryId()))
            validateCategory(desired.categoryId());
        if (active || !current.storageUnitId().equals(desired.storageUnitId()))
            validateUnit(desired.storageUnitId(), "storageUnitId");
        if (active || !current.baseUnitId().equals(desired.baseUnitId()))
            validateUnit(desired.baseUnitId(), "baseUnitId");

        Map<UnitPair, UnitConversion> existing = new HashMap<>();
        current.conversions().forEach(value -> existing.put(new UnitPair(value.fromUnitId(), value.toUnitId()), value));
        for (UnitConversion conversion : desired.conversions()) {
            UnitConversion previous = existing.get(new UnitPair(conversion.fromUnitId(), conversion.toUnitId()));
            boolean changed = previous == null || previous.active() != conversion.active()
                    || previous.factor().compareTo(conversion.factor()) != 0;
            if (active || changed) {
                validateUnit(conversion.fromUnitId(), "conversions.fromUnitId");
                validateUnit(conversion.toUnitId(), "conversions.toUnitId");
            }
        }
    }

    private void validateCategory(UUID id) {
        if (!categories.exists(id))
            throw new ProductReferenceException("categoryId", true);
        if (!categories.isActive(id))
            throw new ProductReferenceException("categoryId", false);
    }

    private void validateUnit(UUID id, String field) {
        if (!units.exists(id))
            throw new ProductReferenceException(field, true);
        if (!units.isActive(id))
            throw new ProductReferenceException(field, false);
    }

    private Map<UUID, String> unitCodes(Product product) {
        var ids = new java.util.LinkedHashSet<UUID>();
        ids.add(product.storageUnitId());
        ids.add(product.baseUnitId());
        product.conversions().forEach(value -> {
            ids.add(value.fromUnitId());
            ids.add(value.toUnitId());
        });
        var result = new LinkedHashMap<UUID, String>();
        ids.forEach(id -> result.put(id, units.code(id)));
        return result;
    }

    private boolean same(ProductDetails current, Product desired, boolean active) {
        Product value = current.product();
        return current.active() == active && value.sku().equals(desired.sku())
                && value.name().equals(desired.name()) && value.categoryId().equals(desired.categoryId())
                && value.productType() == desired.productType()
                && value.storageUnitId().equals(desired.storageUnitId())
                && value.baseUnitId().equals(desired.baseUnitId())
                && value.minStock().compareTo(desired.minStock()) == 0
                && value.requiresLot() == desired.requiresLot()
                && value.requiresHeatNumber() == desired.requiresHeatNumber()
                && value.requiresExpiry() == desired.requiresExpiry()
                && value.requiresSerial() == desired.requiresSerial()
                && sameJson(value.technicalAttributes(), desired.technicalAttributes())
                && sameConversions(value, desired);
    }

    private boolean sameJson(JsonNode existing, JsonNode candidate) {
        if (existing == candidate)
            return true;
        if (existing == null || candidate == null)
            return false;
        if (existing.isNumber() && candidate.isNumber())
            return existing.decimalValue().compareTo(candidate.decimalValue()) == 0;
        if (existing.isObject() && candidate.isObject()) {
            if (existing.size() != candidate.size())
                return false;
            var fields = existing.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!candidate.has(field) || !sameJson(existing.get(field), candidate.get(field)))
                    return false;
            }
            return true;
        }
        if (existing.isArray() && candidate.isArray()) {
            if (existing.size() != candidate.size())
                return false;
            for (int index = 0; index < existing.size(); index++)
                if (!sameJson(existing.get(index), candidate.get(index)))
                    return false;
            return true;
        }
        return existing.equals(candidate);
    }

    private boolean sameConversions(Product current, Product desired) {
        if (current.conversions().size() != desired.conversions().size())
            return false;
        Map<UnitPair, UnitConversion> existing = new HashMap<>();
        current.conversions().forEach(value -> existing.put(new UnitPair(value.fromUnitId(), value.toUnitId()), value));
        for (UnitConversion candidate : desired.conversions()) {
            UnitConversion value = existing.get(new UnitPair(candidate.fromUnitId(), candidate.toUnitId()));
            if (value == null || value.active() != candidate.active()
                    || value.factor().compareTo(candidate.factor()) != 0)
                return false;
        }
        return true;
    }

    private record UnitPair(UUID from, UUID to) {
    }
}
