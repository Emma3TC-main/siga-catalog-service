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
import utp.siga.catalog.application.port.in.CreateProductUseCase;
import utp.siga.catalog.application.port.out.ProductStore;
import utp.siga.catalog.application.usecase.CreateProductService;
import utp.siga.catalog.application.port.in.GetProductUseCase;
import utp.siga.catalog.application.port.in.ListProductsUseCase;
import utp.siga.catalog.application.port.in.UpdateProductUseCase;
import utp.siga.catalog.application.usecase.GetProductService;
import utp.siga.catalog.application.usecase.ListProductsService;
import utp.siga.catalog.application.usecase.UpdateProductService;
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
    @Bean CreateProductUseCase createProduct(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        return new CreateProductService(products, categories, units);
    }
    @Bean GetProductUseCase getProduct(ProductStore products) {
        return new GetProductService(products);
    }
    @Bean ListProductsUseCase listProducts(ProductStore products) {
        return new ListProductsService(products);
    }
    @Bean UpdateProductUseCase updateProduct(ProductStore products, CategoryStore categories, UnitOfMeasureStore units) {
        return new UpdateProductService(products, categories, units);
    }
}
