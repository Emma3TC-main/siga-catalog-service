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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.application.port.in.GetProductUseCase;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.application.port.in.UpdateProductCommand;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductSnapshot;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final CreateProductUseCase createProduct;
    private final GetProductUseCase getProduct;
    private final ListProductsUseCase listProducts;
    private final UpdateProductUseCase updateProduct;

    public ProductController(CreateProductUseCase createProduct, GetProductUseCase getProduct,
            ListProductsUseCase listProducts, UpdateProductUseCase updateProduct) {
        this.createProduct = createProduct;
        this.getProduct = getProduct;
        this.listProducts = listProducts;
        this.updateProduct = updateProduct;
    }

    public record ConversionRequest(@NotNull UUID fromUnitId, @NotNull UUID toUnitId, @NotNull BigDecimal factor,
            Boolean active) {
    }

    public record Request(@NotNull String sku, @NotNull String name, @NotNull UUID categoryId,
            @NotNull Product.Type productType, @NotNull UUID storageUnitId, @NotNull UUID baseUnitId,
            BigDecimal minStock, Boolean requiresLot, Boolean requiresHeatNumber, Boolean requiresExpiry,
            Boolean requiresSerial, JsonNode technicalAttributes, List<@NotNull @Valid ConversionRequest> conversions) {
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

    public record Page(List<Response> content, int page, int size, long totalElements) {
    }

    @GetMapping
    public Page list() {
        var content = listProducts.list().stream().map(this::response).toList();
        // OpenAPI defines ProductPage, but no pagination/filter request parameters.
        return new Page(content, 0, content.size(), content.size());
    }

    @GetMapping("/{id}")
    public Response get(@PathVariable UUID id) {
        return response(getProduct.get(id));
    }

    @PutMapping("/{id}")
    public Response update(@PathVariable UUID id, @Valid @RequestBody Request request,
            HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString((String) servletRequest.getAttribute("correlationId"));
        var command = new UpdateProductCommand(id, request.command(correlationId).asDomain(), correlationId);
        return response(updateProduct.update(command));
    }

    @PostMapping
    public ResponseEntity<Response> create(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString((String) servletRequest.getAttribute("correlationId"));
        var created = createProduct.create(request.command(correlationId));
        return ResponseEntity.status(201).body(response(
                new ProductSnapshot(created.id(), created.active(), created.version(), created.product())));
    }

    private Response response(ProductSnapshot snapshot) {
        var product = snapshot.product();
        return new Response(snapshot.id(), product.sku(), product.name(), product.categoryId(),
                product.productType(),
                product.storageUnitId(), product.baseUnitId(), product.minStock(), product.requiresLot(),
                product.requiresHeatNumber(), product.requiresExpiry(), product.requiresSerial(),
                product.technicalAttributes(),
                product.conversions().stream().map(value -> new ConversionResponse(value.fromUnitId(), value.toUnitId(),
                        value.factor(), value.active())).toList(),
                snapshot.active(), snapshot.version());
    }
}
