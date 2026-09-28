package utp.siga.catalog.domain.model;

public final class UnitFieldValidationException extends IllegalArgumentException {
    private final String field;

    public UnitFieldValidationException(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }
}
