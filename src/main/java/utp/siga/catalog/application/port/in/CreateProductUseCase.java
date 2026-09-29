package utp.siga.catalog.application.port.in;

public interface CreateProductUseCase {
    CreatedProduct create(CreateProductCommand command);

    record CreatedProduct(java.util.UUID id, boolean active, long version, utp.siga.catalog.domain.model.Product product) {}
}
