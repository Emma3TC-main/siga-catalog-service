package utp.siga.catalog.application.port.out;

import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductDetails;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import java.util.Map;
import java.util.UUID;

public interface ProductStore {
    UUID insert(Product product, UUID correlationId, Map<UUID, String> unitCodes);
    ListProductsUseCase.Page findPage(ListProductsUseCase.Query query);
    java.util.Optional<ProductDetails> findById(UUID id);
}
