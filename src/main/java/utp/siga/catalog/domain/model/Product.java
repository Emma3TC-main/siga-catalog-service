package utp.siga.catalog.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record Product(String sku, String name, UUID categoryId, Type productType, UUID storageUnitId,
        UUID baseUnitId, BigDecimal minStock, boolean requiresLot, boolean requiresHeatNumber,
        boolean requiresExpiry, boolean requiresSerial, JsonNode technicalAttributes,
        List<UnitConversion> conversions) {
    public enum Type {
        MATERIAL, INSUMO, REPUESTO, MAQUINARIA
    }

    public Product {
        text(sku, 80, "sku");
        text(name, 200, "name");
        if (categoryId == null)
            throw new ProductFieldValidationException("categoryId");
        if (productType == null)
            throw new ProductFieldValidationException("productType");
        if (storageUnitId == null)
            throw new ProductFieldValidationException("storageUnitId");
        if (baseUnitId == null)
            throw new ProductFieldValidationException("baseUnitId");
        numeric(minStock, "minStock", false);
        if (technicalAttributes == null || !technicalAttributes.isObject())
            throw new ProductFieldValidationException("technicalAttributes");
        conversions = List.copyOf(conversions == null ? List.of() : conversions);
        Set<String> pairs = new HashSet<>();
        for (UnitConversion conversion : conversions) {
            if (conversion == null)
                throw new ProductFieldValidationException("conversions");
            if (!pairs.add(conversion.fromUnitId() + ":" + conversion.toUnitId()))
                throw new ProductFieldValidationException("conversions");
        }
        if (!storageUnitId.equals(baseUnitId) && conversions.stream().noneMatch(conversion -> conversion.active()
                && storageUnitId.equals(conversion.fromUnitId()) && baseUnitId.equals(conversion.toUnitId())))
            throw new ProductFieldValidationException("conversions");
    }

    static void numeric(BigDecimal value, String field, boolean strictlyPositive) {
        if (value == null || (strictlyPositive ? value.signum() <= 0 : value.signum() < 0)
                || value.scale() > 6 || value.precision() - value.scale() > 12)
            throw new ProductFieldValidationException(field);
    }

    private static void text(String value, int maximum, String field) {
        if (value == null || value.codePointCount(0, value.length()) > maximum)
            throw new ProductFieldValidationException(field);
    }
}
