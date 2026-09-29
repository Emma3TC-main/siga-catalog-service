package utp.siga.catalog.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record UnitConversion(UUID fromUnitId, UUID toUnitId, BigDecimal factor, boolean active) {
    public UnitConversion {
        if (fromUnitId == null) throw new ProductFieldValidationException("conversions.fromUnitId");
        if (toUnitId == null) throw new ProductFieldValidationException("conversions.toUnitId");
        if (fromUnitId.equals(toUnitId)) throw new ProductFieldValidationException("conversions.toUnitId");
        Product.numeric(factor, "conversions.factor", true);
    }
}
