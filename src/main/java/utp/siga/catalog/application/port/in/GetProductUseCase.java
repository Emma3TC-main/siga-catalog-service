package utp.siga.catalog.application.port.in;

import java.util.UUID;
import utp.siga.catalog.domain.model.ProductSnapshot;

public interface GetProductUseCase {
    ProductSnapshot get(UUID id);
}
