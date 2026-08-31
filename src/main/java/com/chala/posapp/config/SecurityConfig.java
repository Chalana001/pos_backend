package com.chala.posapp.config;

import com.chala.posapp.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final TenantFilter tenantFilter;
    private final SubscriptionFilter subscriptionFilter;
    private final RateLimitFilter rateLimitFilter; // MISS-02
    private final DuplicateRequestFilter duplicateRequestFilter;
    private final ImpersonationFilter impersonationFilter;
    private final Environment environment;

    private static final String[] SWAGGER_PATHS =
            {"/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html"};

    /**
     * Mirrors {@link com.chala.posapp.security.JwtService}: checks active AND default profiles,
     * because {@code spring.profiles.default=dev} means a dev run often activates nothing.
     */
    private boolean isDevOrTest() {
        Set<String> active = Set.of(environment.getActiveProfiles());
        Set<String> defaults = Set.of(environment.getDefaultProfiles());
        return active.stream().anyMatch(p -> p.contains("dev") || p.contains("test"))
                || defaults.stream().anyMatch(p -> p.contains("dev") || p.contains("test"));
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(Arrays.asList(
                "http://*.localhost:[*]",
                "https://*.localhost:[*]",
                "http://localhost:[*]",
                "https://localhost:[*]",
                "https://admin-panel-pos.vercel.app",
                "https://admin-panel-afzk8r7iw-chalana001s-projects.vercel.app",
                "https://admin.chalanawijesingha.xyz",
                "https://*.chalanawijesingha.xyz",
                "https://chalanawijesingha.xyz",
                "android-app://*"
                // SECURITY FIX: removed "null" — it allows file:// and sandboxed iframes to call the API
        ));

        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // SECURITY FIX: enumerate allowed headers instead of wildcard "*"
        config.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Tenant-ID",
                "X-Requested-With", "Accept", "Origin",
                // checkout sends this on POST /orders; DuplicateRequestFilter reads it
                "Idempotency-Key"
        ));
        config.setExposedHeaders(Arrays.asList(
                "X-Total-Count", "Content-Disposition", "X-Duplicate-Request",
                // Let the POS app show a "support session" banner when an operator is inside.
                "X-Impersonated-By", "X-Impersonation-Read-Only",
                // Lets the POS app warn a shop that it is trading on borrowed time.
                "X-Subscription-Grace", "X-Subscription-Access-Ends"));

        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // SECURITY FIX: add security headers
                .headers(headers -> headers
                        .contentTypeOptions(contentType -> {})
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(xss -> {})
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/saas/plans").permitAll()
                        .requestMatchers("/health").permitAll()
                        // MISS-09: Swagger UI / OpenAPI spec — allow in non-prod only.
                        // In prod this is denyAll rather than authenticated(): the spec lists every
                        // route in the system, and no shop user has any reason to read it.
                        .requestMatchers(SWAGGER_PATHS).access((authentication, context) ->
                                new AuthorizationDecision(isDevOrTest()))
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated()
                )

                // MISS-02: rate limiter runs first, before auth, so bots can't saturate auth
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(subscriptionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs after authentication so a revoked support session is rejected before
                // any controller sees the request, and read-only sessions cannot write.
                .addFilterAfter(impersonationFilter, JwtAuthFilter.class)
                // Runs after authentication so double-submit keys are scoped to
                // the logged-in user and the resolved tenant.
                .addFilterAfter(duplicateRequestFilter, JwtAuthFilter.class);

        return http.build();
    }
}
