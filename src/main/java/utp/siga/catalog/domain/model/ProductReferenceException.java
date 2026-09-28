package utp.siga.catalog.domain.model;

public class ProductReferenceException extends IllegalArgumentException {
    private final String field;
    private final boolean missing;

    public ProductReferenceException(String field, boolean missing) {
        this.field = field;
        this.missing = missing;
    }

    public String field() {
        return field;
    }

    public boolean missing() { return missing; }
}
