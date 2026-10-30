package utp.siga.catalog.application.port.out;
import java.util.*; import utp.siga.catalog.domain.model.Supplier;
public interface SupplierStore { UUID create(Supplier s,UUID c); Optional<Supplier> find(UUID id); Page page(int page,int size,String sort,String q,Boolean active); Update update(Supplier s,long expected,UUID c); record Page(List<Supplier> content,long total){} record Update(Supplier supplier,boolean changed){} }
