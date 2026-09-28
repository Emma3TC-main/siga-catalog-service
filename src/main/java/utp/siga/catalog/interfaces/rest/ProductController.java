package utp.siga.catalog.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.domain.model.Product;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final CreateProductUseCase createProduct;

    public ProductController(CreateProductUseCase createProduct) {
        this.createProduct = createProduct;
    }

    public record ConversionRequest(@NotNull UUID fromUnitId, @NotNull UUID toUnitId, @NotNull BigDecimal factor,
            Boolean active) {
    }

    public record Request(@NotNull String sku, @NotNull String name, @NotNull UUID categoryId,
            @NotNull Product.Type productType, @NotNull UUID storageUnitId, @NotNull UUID baseUnitId,
            BigDecimal minStock, Boolean requiresLot, Boolean requiresHeatNumber, Boolean requiresExpiry,
            Boolean requiresSerial, JsonNode technicalAttributes, List<@Valid ConversionRequest> conversions) {
        CreateProductCommand command(UUID correlationId) {
            return new CreateProductCommand(sku, name, categoryId, productType, storageUnitId, baseUnitId, minStock,
                    requiresLot, requiresHeatNumber, requiresExpiry, requiresSerial, technicalAttributes,
                    conversions == null ? null
                            : conversions.stream().map(value -> new CreateProductCommand.Conversion(
                                    value.fromUnitId(), value.toUnitId(), value.factor(), value.active())).toList(),
                    correlationId);
        }
    }

    public record Response(UUID id, String sku, String name, UUID categoryId, Product.Type productType,
            UUID storageUnitId, UUID baseUnitId, BigDecimal minStock, boolean requiresLot,
            boolean requiresHeatNumber, boolean requiresExpiry, boolean requiresSerial,
            JsonNode technicalAttributes, List<ConversionResponse> conversions, boolean active, long version) {
    }

    public record ConversionResponse(UUID fromUnitId, UUID toUnitId, BigDecimal factor, boolean active) {
    }

    @PostMapping
    public ResponseEntity<Response> create(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString((String) servletRequest.getAttribute("correlationId"));
        var created = createProduct.create(request.command(correlationId));
        var product = created.product();
        var body = new Response(created.id(), product.sku(), product.name(), product.categoryId(),
                product.productType(),
                product.storageUnitId(), product.baseUnitId(), product.minStock(), product.requiresLot(),
                product.requiresHeatNumber(), product.requiresExpiry(), product.requiresSerial(),
                product.technicalAttributes(),
                product.conversions().stream().map(value -> new ConversionResponse(value.fromUnitId(), value.toUnitId(),
                        value.factor(), value.active())).toList(),
                created.active(), created.version());
        return ResponseEntity.status(201).body(body);
    }
}
