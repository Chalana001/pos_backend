package com.chala.posapp.config;

import com.chala.posapp.security.JwtService;
import com.chala.posapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenantFromUrl = extractTenantFromUrl(request);
        String tenantFromToken = null;

        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);
            try {
                tenantFromToken = jwtService.extractTenantId(jwt);
            } catch (Exception e) {
                System.out.println("Invalid or Expired JWT");
            }
        }

        if (tenantFromToken != null) {
            if (!tenantFromToken.equals(tenantFromUrl)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant mismatch! You cannot access this shop.");
                return;
            }
            TenantContext.setTenant(tenantFromToken);
        }
        else if (tenantFromUrl != null && !tenantFromUrl.isEmpty()) {
            TenantContext.setTenant(tenantFromUrl);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractTenantFromUrl(HttpServletRequest request) {
        String host = request.getServerName();
        if (host.equals("localhost") || host.startsWith("127.0.0.1")) {
            String tenant = request.getHeader("X-Tenant-ID");
            return (tenant != null) ? tenant : request.getParameter("tenant");
        } else {
            return host.split("\\.")[0];
        }
    }
}