package utp.siga.catalog.domain.model;

import java.util.UUID;

public record ProductDetails(UUID id, Product product, boolean active, long version) { }
