package utp.siga.catalog.application.port.in;

import java.util.List;
import java.util.UUID;
import utp.siga.catalog.domain.model.Product;
import utp.siga.catalog.domain.model.ProductDetails;

public interface ListProductsUseCase {
    Page list(Query query);
    record Query(int page, int size, Sort sort, String q, Boolean active, UUID categoryId, Product.Type productType) { }
    enum Sort { SKU_ASC, SKU_DESC, NAME_ASC, NAME_DESC }
    record Page(List<ProductDetails> content, int page, int size, long totalElements) { }
}
