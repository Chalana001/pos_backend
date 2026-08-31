package com.chala.posapp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MISS-09: Swagger / OpenAPI 3 configuration.
 *
 * Access the UI at: http://localhost:8080/swagger-ui/index.html
 * Access the spec at: http://localhost:8080/v3/api-docs
 *
 * To authenticate: click "Authorize" and enter: Bearer <your-jwt-token>
 *
 * SECURITY NOTE: In production, restrict Swagger access via SecurityConfig or
 * by setting springdoc.swagger-ui.enabled=false in the production profile.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI posOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS System API")
                        .description("""
                                Multi-tenant Point of Sale backend API.
                                
                                **Authentication**: All endpoints (except /api/auth/login) require a
                                Bearer JWT token. Obtain one from POST /api/auth/login and click
                                the Authorize button above.
                                
                                **Tenant**: Every request must include the `X-Tenant-ID` header
                                identifying which shop/tenant is being accessed.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("POS Dev Team")
                                .email("dev@example.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://example.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local dev"),
                        new Server().url("https://api.chalanawijesingha.xyz").description("Production")
                ))
                // JWT Bearer security scheme
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter JWT token obtained from POST /api/auth/login")));
    }
}
