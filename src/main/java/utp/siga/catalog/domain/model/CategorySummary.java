package utp.siga.catalog.domain.model;

import java.util.UUID;

public record CategorySummary(UUID id, String code, String name, Category.Type categoryType, boolean active) {}
