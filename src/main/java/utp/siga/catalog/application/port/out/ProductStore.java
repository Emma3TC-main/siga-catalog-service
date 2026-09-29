package utp.siga.catalog.application.port.out;

import utp.siga.catalog.domain.model.Product;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import utp.siga.catalog.domain.model.ProductSnapshot;

public interface ProductStore {
    UUID insert(Product product, UUID correlationId, Map<UUID, String> unitCodes);
    List<ProductSnapshot> findAllOrderBySku();
    Optional<ProductSnapshot> findById(UUID id);
    boolean lock(UUID id);
    ProductSnapshot update(UUID id, Product product, UUID correlationId, Map<UUID, String> unitCodes);
}
