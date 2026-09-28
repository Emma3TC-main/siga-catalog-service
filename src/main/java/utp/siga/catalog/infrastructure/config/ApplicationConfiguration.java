package utp.siga.catalog.infrastructure.config;
import org.springframework.context.annotation.*;
import utp.siga.catalog.application.port.in.CreateCategoryUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.usecase.CreateCategoryService;
import utp.siga.catalog.application.port.in.ListCategoriesUseCase;
import utp.siga.catalog.application.usecase.ListCategoriesService;
import utp.siga.catalog.application.port.in.CreateUnitOfMeasureUseCase;
import utp.siga.catalog.application.port.out.UnitOfMeasureStore;
import utp.siga.catalog.application.usecase.CreateUnitOfMeasureService;
import utp.siga.catalog.application.port.in.ListUnitsOfMeasureUseCase;
import utp.siga.catalog.application.usecase.ListUnitsOfMeasureService;
@Configuration
public class ApplicationConfiguration {
    @Bean CreateCategoryUseCase createCategory(CategoryStore store) { return new CreateCategoryService(store); }
    @Bean ListCategoriesUseCase listCategories(CategoryStore store) { return new ListCategoriesService(store); }
    @Bean CreateUnitOfMeasureUseCase createUnitOfMeasure(UnitOfMeasureStore store) {
        return new CreateUnitOfMeasureService(store);
    }
    @Bean ListUnitsOfMeasureUseCase listUnitsOfMeasure(UnitOfMeasureStore store) {
        return new ListUnitsOfMeasureService(store);
    }
}
