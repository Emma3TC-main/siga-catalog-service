package utp.siga.catalog.application.port.in;

import java.util.UUID;
import utp.siga.catalog.domain.model.Product;

/** The complete ProductRequest already used by creation, addressed to an existing product. */
public record UpdateProductCommand(UUID id, Product product, UUID correlationId) {
}
