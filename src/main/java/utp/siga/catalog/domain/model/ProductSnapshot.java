package utp.siga.catalog.domain.model;

import java.util.UUID;

public record ProductSnapshot(UUID id, boolean active, long version, Product product) {
}
