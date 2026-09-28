package utp.siga.catalog.domain.model;

import java.util.UUID;

public record UnitOfMeasureSummary(UUID id, String code, String name, String symbol, String dimension, boolean active) {}
