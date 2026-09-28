package utp.siga.catalog.infrastructure.config;
import org.springframework.context.annotation.*;
import utp.siga.catalog.application.port.in.CreateCategoryUseCase;
import utp.siga.catalog.application.port.out.CategoryStore;
import utp.siga.catalog.application.usecase.CreateCategoryService;
@Configuration
public class ApplicationConfiguration {
    @Bean CreateCategoryUseCase createCategory(CategoryStore store) { return new CreateCategoryService(store); }
}
