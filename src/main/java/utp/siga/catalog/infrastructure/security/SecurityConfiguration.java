package utp.siga.catalog.infrastructure.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import utp.siga.catalog.interfaces.rest.Problems;
@Configuration
public class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${catalog.security.jwks-uri}") String jwks,
            @Value("${catalog.security.issuer}") String issuer, @Value("${catalog.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> jwt.getAudience().contains(audience)
                && jwt.getExpiresAt() != null && jwt.getSubject() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/categories", "/api/v1/units").hasAuthority("PRODUCT_WRITE")
                .requestMatchers(HttpMethod.POST,"/api/v1/products").hasAuthority("PRODUCT_WRITE")
                .requestMatchers(HttpMethod.GET,"/api/v1/products", "/api/v1/products/*").hasAuthority("PRODUCT_WRITE")
                .requestMatchers(HttpMethod.PUT,"/api/v1/products/*").hasAuthority("PRODUCT_WRITE")
                .requestMatchers(HttpMethod.GET,"/api/v1/categories", "/api/v1/units").hasAuthority("PRODUCT_WRITE")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,x) -> Problems.write(req,res,401,"AUTH_INVALID"))
                .accessDeniedHandler((req,res,x) -> Problems.write(req,res,403,"AUTH_FORBIDDEN")))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req,res,x) -> Problems.write(req,res,401,"AUTH_INVALID"))
                .accessDeniedHandler((req,res,x) -> Problems.write(req,res,403,"AUTH_FORBIDDEN")));
        return http.build();
    }
}
