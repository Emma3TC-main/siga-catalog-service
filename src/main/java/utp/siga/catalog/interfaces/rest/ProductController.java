package utp.siga.catalog.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.catalog.application.port.in.CreateProductCommand;
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.application.port.in.GetProductUseCase;
import utp.siga.catalog.application.port.in.UpdateProductCommand;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.domain.model.Product;

@RestController
@Validated
@RequestMapping("/api/v1/products")
public class ProductController {
    private final CreateProductUseCase createProduct;
    private final ListProductsUseCase listProducts;
    private final GetProductUseCase getProduct;
    private final UpdateProductUseCase updateProduct;

    public ProductController(CreateProductUseCase createProduct, ListProductsUseCase listProducts,
            GetProductUseCase getProduct, UpdateProductUseCase updateProduct) {
        this.createProduct = createProduct;
        this.listProducts = listProducts;
        this.getProduct = getProduct;
        this.updateProduct = updateProduct;
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

    public record UpdateConversionRequest(@NotNull UUID fromUnitId, @NotNull UUID toUnitId,
            @NotNull BigDecimal factor, @NotNull Boolean active) {
        UpdateProductCommand.Conversion command() {
            return new UpdateProductCommand.Conversion(fromUnitId, toUnitId, factor, active);
        }
    }

    public record UpdateRequest(@NotNull String sku, @NotNull String name, @NotNull UUID categoryId,
            @NotNull Product.Type productType, @NotNull UUID storageUnitId, @NotNull UUID baseUnitId,
            @NotNull BigDecimal minStock, @NotNull Boolean requiresLot, @NotNull Boolean requiresHeatNumber,
            @NotNull Boolean requiresExpiry, @NotNull Boolean requiresSerial, @NotNull JsonNode technicalAttributes,
            @NotNull List<@Valid UpdateConversionRequest> conversions, @NotNull Boolean active,
            @NotNull Long expectedVersion) {
        UpdateProductCommand command(UUID correlationId) {
            return new UpdateProductCommand(sku, name, categoryId, productType, storageUnitId, baseUnitId,
                    minStock, requiresLot, requiresHeatNumber, requiresExpiry, requiresSerial, technicalAttributes,
                    conversions.stream().map(UpdateConversionRequest::command).toList(), active, expectedVersion,
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
    public record Page(List<Response> content, int page, int size, long totalElements) { }

    @GetMapping
    public Page list(@RequestParam(defaultValue="0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue="20") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size,
            @RequestParam(defaultValue="sku,asc") String sort, @RequestParam(required=false) String q,
            @RequestParam(required=false) Boolean active, @RequestParam(required=false) UUID categoryId,
            @RequestParam(required=false) Product.Type productType) {
        var result=listProducts.list(new ListProductsUseCase.Query(page,size,sort(sort),q,active,categoryId,productType));
        return new Page(result.content().stream().map(this::response).toList(),result.page(),result.size(),result.totalElements());
    }
    @GetMapping("/{id}") public Response get(@PathVariable UUID id) { return response(getProduct.get(id)); }

    @PostMapping
    public ResponseEntity<Response> create(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString((String) servletRequest.getAttribute("correlationId"));
        var created = createProduct.create(request.command(correlationId));
        var body = response(new utp.siga.catalog.domain.model.ProductDetails(created.id(),created.product(),created.active(),created.version()));
        return ResponseEntity.status(201).body(body);
    }
    @PutMapping("/{id}")
    public Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request,
            HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString((String) servletRequest.getAttribute("correlationId"));
        return response(updateProduct.update(id, request.command(correlationId)));
    }
    private Response response(utp.siga.catalog.domain.model.ProductDetails value) { var product=value.product(); return new Response(value.id(),product.sku(),product.name(),product.categoryId(),product.productType(),product.storageUnitId(),product.baseUnitId(),product.minStock(),product.requiresLot(),product.requiresHeatNumber(),product.requiresExpiry(),product.requiresSerial(),product.technicalAttributes(),product.conversions().stream().map(x -> new ConversionResponse(x.fromUnitId(),x.toUnitId(),x.factor(),x.active())).toList(),value.active(),value.version()); }
    private ListProductsUseCase.Sort sort(String value) { return switch(value) { case "sku,asc" -> ListProductsUseCase.Sort.SKU_ASC; case "sku,desc" -> ListProductsUseCase.Sort.SKU_DESC; case "name,asc" -> ListProductsUseCase.Sort.NAME_ASC; case "name,desc" -> ListProductsUseCase.Sort.NAME_DESC; default -> throw new IllegalArgumentException("sort"); }; }
}
