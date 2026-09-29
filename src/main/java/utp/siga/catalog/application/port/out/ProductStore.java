package utp.siga.catalog.application.port.out;

import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductDetails;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import utp.siga.catalog.domain.model.CategorySummary;

public interface ProductStore {
    UUID insert(Product product, UUID correlationId, Map<UUID, String> unitCodes);
    ListProductsUseCase.Page findPage(ListProductsUseCase.Query query);
    Optional<ProductDetails> findById(UUID id);
    Optional<ProductDetails> findByIdForUpdate(UUID id);
    ProductDetails update(UUID id, Product product, boolean active, long expectedVersion, UUID correlationId,
            Map<UUID, String> unitCodes, CategorySummary category);
}
