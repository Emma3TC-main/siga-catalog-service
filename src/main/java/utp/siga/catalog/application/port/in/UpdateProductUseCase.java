package utp.siga.catalog.application.port.in;

import utp.siga.catalog.domain.model.ProductSnapshot;

public interface UpdateProductUseCase {
    ProductSnapshot update(UpdateProductCommand command);
}
