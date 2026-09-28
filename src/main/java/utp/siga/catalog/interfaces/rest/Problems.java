package utp.siga.catalog.interfaces.rest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import utp.siga.catalog.domain.model.UnitFieldValidationException;
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
    private String unitField(String field) { return "Revisa el campo " + field + " de la unidad"; }
    @ExceptionHandler(UnitFieldValidationException.class)
    ResponseEntity<?> invalidUnit(UnitFieldValidationException e,HttpServletRequest req) {
        return response(req,400,"VALIDATION_ERROR",unitField(e.field()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalidFields(MethodArgumentNotValidException e,HttpServletRequest req) {
        if (units(req) && e.getBindingResult().getFieldError() != null)
            return response(req,400,"VALIDATION_ERROR",unitField(e.getBindingResult().getFieldError().getField()));
        return response(req,400,"VALIDATION_ERROR","Revisa los campos de la categoría");
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpServletRequest req) {
        return response(req,400,"VALIDATION_ERROR",units(req) ? "Revisa el cuerpo de la unidad" : "Revisa los campos de la categoría");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(HttpServletRequest req) { return response(req,400,"VALIDATION_ERROR","Revisa los campos de la categoría"); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception e,HttpServletRequest req) { return response(req,409,"DATA_CONFLICT","El código de categoría ya existe"); }
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<?> unavailable(Exception e,HttpServletRequest req) { return response(req,503,"DEPENDENCY_UNAVAILABLE","Base de datos no disponible"); }
}
