package utp.siga.catalog.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.catalog.application.port.in.CreateUnitOfMeasureUseCase;
import utp.siga.catalog.application.port.in.ListUnitsOfMeasureUseCase;
import utp.siga.catalog.domain.model.UnitOfMeasure;

@RestController
@RequestMapping("/api/v1/units")
public class UnitController {
    private final CreateUnitOfMeasureUseCase createUnit;
    private final ListUnitsOfMeasureUseCase listUnits;

    public UnitController(CreateUnitOfMeasureUseCase createUnit, ListUnitsOfMeasureUseCase listUnits) {
        this.createUnit = createUnit;
        this.listUnits = listUnits;
    }

    public record Request(@NotNull String code, @NotNull String name, @NotNull String symbol,
                          @NotNull String dimension) {}
    public record Response(UUID id, String code, String name, String symbol, String dimension, boolean active) {}

    @GetMapping
    public List<Response> list() {
        return listUnits.list().stream()
                .map(unit -> new Response(unit.id(), unit.code(), unit.name(), unit.symbol(), unit.dimension(), unit.active()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody Request request) {
        createUnit.create(new UnitOfMeasure(request.code(), request.name(), request.symbol(), request.dimension()));
        return ResponseEntity.status(201).build();
    }
}
