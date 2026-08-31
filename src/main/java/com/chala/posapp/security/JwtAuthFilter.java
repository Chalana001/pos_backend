package com.chala.posapp.security;

import com.chala.posapp.entity.User;
import com.chala.posapp.service.UserService;
import io.jsonwebtoken.ExpiredJwtException; // ✅ මේක අනිවාර්යයෙන්ම import කරන්න
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserService userService;
    private final TokenDenyList tokenDenyList;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/auth/login")
                || path.equals("/api/auth/login")
                || path.equals("/api/saas/plans")
                || path.equals("/health")
                || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            String username = jwtService.extractUsername(token);

            // Logged out on this device: the token is still well-formed and unexpired, so
            // only the deny-list can tell us it is finished.
            if (tokenDenyList.isRevoked(jwtService.extractTokenId(token))) {
                handleException(response, "Session ended. Please login again.",
                        HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                User account = userService.getByUsername(username);

                if (!account.isEnabled()) {
                    handleException(response, "User disabled.", HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                if (isIssuedBeforeWatermark(token, account)) {
                    handleException(response, "Session ended. Please login again.",
                            HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                UserDetails userDetails = userService.toUserDetails(account);

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            handleException(response, "Token has expired. Please login again.", HttpServletResponse.SC_UNAUTHORIZED);
        } catch (JwtException e) {
            handleException(response, "Invalid token.", HttpServletResponse.SC_UNAUTHORIZED);
        } catch (Exception e) {
            handleException(response, "Authentication failed.", HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    /**
     * True when this token predates the user's {@code token_valid_from} watermark — i.e. it was
     * minted before a password reset or a "sign out everywhere".
     *
     * <p>The watermark is truncated to whole seconds before comparing because a JWT's {@code
     * iat} only has second precision. Without that, logging straight back in within the same
     * second as the reset produced a token whose iat (12:00:00) looked older than the watermark
     * (12:00:00.4) and the fresh session was rejected on its first request.
     */
    private boolean isIssuedBeforeWatermark(String token, User account) {
        if (account.getTokenValidFrom() == null) {
            return false;
        }
        Instant issuedAt = jwtService.extractIssuedAt(token);
        if (issuedAt == null) {
            // A token with no iat cannot be shown to be newer than the watermark, and the
            // watermark exists precisely because something went wrong — fail closed.
            return true;
        }
        Instant watermark = account.getTokenValidFrom()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS);
        return issuedAt.isBefore(watermark);
    }

    private void handleException(HttpServletResponse response, String message, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\": \"" + message + "\", \"status\": " + status + "}");
    }
}