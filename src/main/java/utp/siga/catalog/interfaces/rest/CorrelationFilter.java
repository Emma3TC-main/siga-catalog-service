package utp.siga.catalog.interfaces.rest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        UUID id;
        try { id = UUID.fromString(req.getHeader("X-Correlation-ID")); }
        catch (Exception ignored) { id = UUID.randomUUID(); }
        req.setAttribute("correlationId", id.toString());
        res.setHeader("X-Correlation-ID", id.toString());
        chain.doFilter(req, res);
    }
}
