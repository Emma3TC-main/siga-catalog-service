package utp.siga.catalog.application.port.out;

import utp.siga.catalog.domain.model.Product;
import java.util.Map;
import java.util.UUID;

public interface ProductStore {
    UUID insert(Product product, UUID correlationId, Map<UUID, String> unitCodes);
}
