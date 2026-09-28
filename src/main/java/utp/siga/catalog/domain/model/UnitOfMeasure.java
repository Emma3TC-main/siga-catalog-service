package utp.siga.catalog.domain.model;

public record UnitOfMeasure(String code, String name, String symbol, String dimension) {
    public UnitOfMeasure {
        if (code == null || code.codePointCount(0, code.length()) > 20)
            throw new UnitFieldValidationException("code");
        if (name == null || name.codePointCount(0, name.length()) > 80)
            throw new UnitFieldValidationException("name");
        if (symbol == null || symbol.codePointCount(0, symbol.length()) > 20)
            throw new UnitFieldValidationException("symbol");
        if (dimension == null || dimension.codePointCount(0, dimension.length()) > 40)
            throw new UnitFieldValidationException("dimension");
    }
}
