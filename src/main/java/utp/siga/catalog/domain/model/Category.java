package utp.siga.catalog.domain.model;
public record Category(String code, String name, Type categoryType) {
    public enum Type { MATERIAL, INSUMO, REPUESTO, MAQUINARIA }
    public Category {
        if (code == null || code.codePointCount(0, code.length()) > 50 || name == null
                || name.codePointCount(0, name.length()) > 120 || categoryType == null)
            throw new IllegalArgumentException("Categoría incompatible con el modelo canónico");
    }
}
