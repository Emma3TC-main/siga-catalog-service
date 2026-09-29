package utp.siga.catalog.interfaces.rest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utp.siga.catalog.application.port.in.CreateCategoryUseCase;
import utp.siga.catalog.application.port.in.ListCategoriesUseCase;
import utp.siga.catalog.domain.model.Category;
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CreateCategoryUseCase createCategory;
    private final ListCategoriesUseCase listCategories;
    public CategoryController(CreateCategoryUseCase createCategory, ListCategoriesUseCase listCategories) {
        this.createCategory = createCategory;
        this.listCategories = listCategories;
    }
    public record Request(@NotNull String code, @NotNull String name, @NotNull Category.Type categoryType) {}
    public record Response(java.util.UUID id, String code, String name, Category.Type categoryType, boolean active) {}
    @GetMapping
    public List<Response> list() {
        return listCategories.list().stream()
                .map(category -> new Response(category.id(), category.code(), category.name(), category.categoryType(), category.active()))
                .toList();
    }
    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody Request request) {
        createCategory.create(new Category(request.code(), request.name(), request.categoryType()));
        return ResponseEntity.status(201).build();
    }
}
