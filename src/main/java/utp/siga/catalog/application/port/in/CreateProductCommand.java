package utp.siga.catalog.application.port.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.UnitConversion;

public record CreateProductCommand(String sku, String name, UUID categoryId, Product.Type productType,
        UUID storageUnitId, UUID baseUnitId, BigDecimal minStock, Boolean requiresLot,
        Boolean requiresHeatNumber, Boolean requiresExpiry, Boolean requiresSerial,
        JsonNode technicalAttributes, List<Conversion> conversions, UUID correlationId) {

    public record Conversion(UUID fromUnitId, UUID toUnitId, BigDecimal factor, Boolean active) {
        UnitConversion asDomain() {
            return new UnitConversion(fromUnitId, toUnitId, factor, active == null || active);
        }
    }

    public Product asDomain() {
        return new Product(sku, name, categoryId, productType, storageUnitId, baseUnitId,
                minStock == null ? BigDecimal.ZERO : minStock, Boolean.TRUE.equals(requiresLot),
                Boolean.TRUE.equals(requiresHeatNumber), Boolean.TRUE.equals(requiresExpiry),
                Boolean.TRUE.equals(requiresSerial),
                technicalAttributes == null ? JsonNodeFactory.instance.objectNode() : technicalAttributes,
                conversions == null ? List.of() : conversions.stream().map(Conversion::asDomain).toList());
    }
}
