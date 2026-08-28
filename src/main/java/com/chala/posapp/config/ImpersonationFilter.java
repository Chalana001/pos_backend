package com.chala.posapp.config;

import com.chala.posapp.entity.ImpersonationSession;
import com.chala.posapp.security.JwtService;
import com.chala.posapp.service.ImpersonationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Polices support-session tokens.
 *
 * <p>A normal login token passes straight through. A token carrying an {@code impersonatedBy}
 * claim is checked against {@code impersonation_sessions}:
 * <ul>
 *   <li>revoked or expired row → 401, so revoking a session ends it on the next request rather
 *       than whenever the token would have expired</li>
 *   <li>{@code readOnly} session attempting a write verb → 403</li>
 * </ul>
 *
 * <p>Read-only is enforced here rather than in the UI on purpose: hiding buttons stops
 * accidents, not mistakes made with a copied token.
 *
 * <p>The response also carries {@code X-Impersonated-By} so the POS app can show the operator
 * a persistent "you are inside someone else's shop" banner.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImpersonationFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final JwtService jwtService;
    private final ImpersonationService impersonationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        String impersonatedBy;
        String tokenId;
        boolean readOnly;
        try {
            impersonatedBy = jwtService.extractImpersonatedBy(token);
            if (impersonatedBy == null) {
                filterChain.doFilter(request, response);
                return;
            }
            tokenId = jwtService.extractTokenId(token);
            readOnly = jwtService.extractReadOnly(token);
        } catch (Exception exception) {
            // A malformed or expired token is JwtAuthFilter's problem, not this filter's.
            filterChain.doFilter(request, response);
            return;
        }

        if (tokenId == null || tokenId.isBlank()) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "SUPPORT_SESSION_INVALID",
                    "This support session token is malformed.");
            return;
        }

        Optional<ImpersonationSession> session = impersonationService.validate(tokenId);
        if (session.isEmpty()) {
            log.warn("Rejected a revoked or expired support session. tokenId={}, actor={}", tokenId, impersonatedBy);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "SUPPORT_SESSION_ENDED",
                    "This support session has ended. Open a new one from the control panel.");
            return;
        }

        if (readOnly && WRITE_METHODS.contains(request.getMethod().toUpperCase())) {
            log.warn("Blocked a write from a read-only support session. actor={}, tenant={}, {} {}",
                    impersonatedBy, session.get().getTenantId(), request.getMethod(), request.getRequestURI());
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "SUPPORT_SESSION_READ_ONLY",
                    "This is a read-only support session. Re-open it with write access to make changes.");
            return;
        }

        impersonationService.touch(tokenId);
        response.setHeader("X-Impersonated-By", impersonatedBy);
        response.setHeader("X-Impersonation-Read-Only", String.valueOf(readOnly));

        filterChain.doFilter(request, response);
    }

    private void writeJson(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"" + message.replace("\"", "\\\"") + "\",\"code\":\"" + code + "\"}");
    }
}
