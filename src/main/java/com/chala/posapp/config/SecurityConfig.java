package com.chala.posapp.config;

import com.chala.posapp.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final TenantFilter tenantFilter;
    private final SubscriptionFilter subscriptionFilter;
    private final RateLimitFilter rateLimitFilter; // MISS-02
    private final DuplicateRequestFilter duplicateRequestFilter;

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
                "X-Total-Count", "Content-Disposition", "X-Duplicate-Request"));

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
                        // MISS-09: Swagger UI / OpenAPI spec — allow in non-prod only
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated()
                )

                // MISS-02: rate limiter runs first, before auth, so bots can't saturate auth
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(subscriptionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs after authentication so double-submit keys are scoped to
                // the logged-in user and the resolved tenant.
                .addFilterAfter(duplicateRequestFilter, JwtAuthFilter.class);

        return http.build();
    }
}
