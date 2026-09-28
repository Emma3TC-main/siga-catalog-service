package utp.siga.catalog.interfaces.rest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utp.siga.catalog.application.port.in.CreateCategoryUseCase;
import utp.siga.catalog.domain.model.Category;
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CreateCategoryUseCase createCategory;
    public CategoryController(CreateCategoryUseCase createCategory) { this.createCategory = createCategory; }
    public record Request(@NotNull String code, @NotNull String name, @NotNull Category.Type categoryType) {}
    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody Request request) {
        createCategory.create(new Category(request.code(), request.name(), request.categoryType()));
        return ResponseEntity.status(201).build();
    }
}
