package utp.siga.catalog.interfaces.rest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import utp.siga.catalog.domain.model.UnitFieldValidationException;
import utp.siga.catalog.domain.model.ProductFieldValidationException;
import utp.siga.catalog.domain.model.ProductReferenceException;
@RestControllerAdvice
public class Problems {
    public static Map<String,Object> body(HttpServletRequest req, int status, String code, String detail) {
        return Map.of("type", "about:blank", "title", HttpStatus.valueOf(status).getReasonPhrase(),
                "status", status, "code", code, "detail", detail, "instance", req.getRequestURI(),
                "correlationId", req.getAttribute("correlationId"));
    }
    public static void write(HttpServletRequest req, HttpServletResponse res, int status, String code) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        new ObjectMapper().writeValue(res.getOutputStream(), body(req,status,code,"Solicitud no autorizada"));
    }
    private ResponseEntity<?> response(HttpServletRequest req,int status,String code,String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body(req,status,code,detail));
    }
    private boolean units(HttpServletRequest req) { return "/api/v1/units".equals(req.getRequestURI()); }
    private boolean products(HttpServletRequest req) { return "/api/v1/products".equals(req.getRequestURI()); }
    private String unitField(String field) { return "Revisa el campo " + field + " de la unidad"; }
    @ExceptionHandler(UnitFieldValidationException.class)
    ResponseEntity<?> invalidUnit(UnitFieldValidationException e,HttpServletRequest req) {
        return response(req,400,"VALIDATION_ERROR",unitField(e.field()));
    }
    @ExceptionHandler(ProductFieldValidationException.class)
    ResponseEntity<?> invalidProduct(ProductFieldValidationException e,HttpServletRequest req) {
        return response(req,400,"VALIDATION_ERROR","Revisa el campo " + e.field() + " del producto");
    }
    @ExceptionHandler(ProductReferenceException.class)
    ResponseEntity<?> productReference(ProductReferenceException e,HttpServletRequest req) {
        String resource = e.field().equals("categoryId") ? "CATEGORY" : "UNIT_OF_MEASURE";
        int status = e.missing() ? 404 : 409;
        String code = resource + (e.missing() ? "_NOT_FOUND" : "_INACTIVE");
        Map<String,Object> body = new HashMap<>(body(req,status,code,
                e.missing() ? "La referencia indicada no existe" : "La referencia indicada está inactiva"));
        body.put("field",e.field());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalidFields(MethodArgumentNotValidException e,HttpServletRequest req) {
        if (products(req) && e.getBindingResult().getFieldError() != null)
            return response(req,400,"VALIDATION_ERROR","Revisa el campo " + e.getBindingResult().getFieldError().getField() + " del producto");
        if (units(req) && e.getBindingResult().getFieldError() != null)
            return response(req,400,"VALIDATION_ERROR",unitField(e.getBindingResult().getFieldError().getField()));
        return response(req,400,"VALIDATION_ERROR","Revisa los campos de la categoría");
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpServletRequest req) {
        return response(req,400,"VALIDATION_ERROR",products(req) ? "Revisa el cuerpo del producto" : units(req) ? "Revisa el cuerpo de la unidad" : "Revisa los campos de la categoría");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(HttpServletRequest req) { return response(req,400,"VALIDATION_ERROR",products(req) ? "Revisa los campos del producto" : "Revisa los campos de la categoría"); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception e,HttpServletRequest req) { return response(req,409,"DATA_CONFLICT",products(req) ? "El SKU de producto ya existe" : "El código de categoría ya existe"); }
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<?> unavailable(Exception e,HttpServletRequest req) { return response(req,503,"DEPENDENCY_UNAVAILABLE","Base de datos no disponible"); }
}
