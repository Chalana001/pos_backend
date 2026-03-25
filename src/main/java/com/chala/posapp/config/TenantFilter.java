package com.chala.posapp.config;

import com.chala.posapp.security.JwtService;
import com.chala.posapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenantFromRequest = extractTenantFromUrl(request);
        String tenantFromToken = null;

        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);
            try {
                tenantFromToken = jwtService.extractTenantId(jwt);
            } catch (Exception e) {
                log.warn("Invalid or Expired JWT");
            }
        }

        if (tenantFromToken != null) {
            if (tenantFromToken.equals("MASTER")) {
                TenantContext.setTenant("MASTER");
            }
            else {
                if (tenantFromRequest != null && !tenantFromToken.equals(tenantFromRequest)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant mismatch! You cannot access this shop.");
                    return;
                }
                TenantContext.setTenant(tenantFromToken);
            }
        }
        else if (tenantFromRequest != null && !tenantFromRequest.isEmpty()) {
            TenantContext.setTenant(tenantFromRequest);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractTenantFromUrl(HttpServletRequest request) {
        String tenantHeader = request.getHeader("X-Tenant-ID");
        if (tenantHeader != null && !tenantHeader.isEmpty()) {
            return tenantHeader;
        }

        String host = request.getServerName();
        if (host.equals("localhost") || host.startsWith("127.0.0.1") || isIpAddress(host)) {
            return request.getParameter("tenant");
        } else {
            String[] parts = host.split("\\.");
            return (parts.length > 1) ? parts[0] : null;
        }
    }

    private boolean isIpAddress(String host) {
        return IPV4_PATTERN.matcher(host).matches();
    }
}
