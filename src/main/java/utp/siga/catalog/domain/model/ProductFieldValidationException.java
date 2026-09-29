package utp.siga.catalog.domain.model;

public class ProductFieldValidationException extends IllegalArgumentException {
    private final String field;

    public ProductFieldValidationException(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }
}
