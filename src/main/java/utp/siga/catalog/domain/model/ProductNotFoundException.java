package utp.siga.catalog.domain.model;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException() {
        super("El producto indicado no existe");
    }
}
