package utp.siga.catalog.application.port.in;

import java.util.UUID;
import utp.siga.catalog.domain.model.ProductDetails;

public interface GetProductUseCase { ProductDetails get(UUID id); }
