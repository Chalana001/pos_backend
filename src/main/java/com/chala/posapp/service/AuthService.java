package com.chala.posapp.service;

import com.chala.posapp.dto.user.AuthResponse;
import com.chala.posapp.dto.user.CurrentUserResponse;
import com.chala.posapp.dto.user.LoginRequest;
import com.chala.posapp.dto.user.OfflinePinRequest;
import com.chala.posapp.dto.user.OfflinePinStatusResponse;
import com.chala.posapp.dto.user.RegisterRequest;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.security.JwtService;
import com.chala.posapp.security.LoginAttemptService;
import com.chala.posapp.security.TokenDenyList;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PlatformTransactionManager transactionManager;
    private final LoginAttemptService loginAttemptService;
    private final TokenDenyList tokenDenyList;
    private final SuperAdminAuditService auditService;

    @Transactional
    public void registerAdmin(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername()))
            throw new AlreadyExistsException("Username already exists");

        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.ADMIN)
                .enabled(true)
                .branchId(null)
                .build();

        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request) {
        String tenantId = TenantContext.getTenant();
        String attemptedUsername = request.getUsername();

        loginAttemptService.assertNotLocked(tenantId, attemptedUsername);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(attemptedUsername, request.getPassword())
            );
        } catch (AuthenticationException exception) {
            onFailedLogin(tenantId, attemptedUsername);
            throw exception;
        }

        loginAttemptService.recordSuccess(tenantId, attemptedUsername);

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isEnabled()) throw new BadRequestException("User disabled");

        String token = jwtService.generateToken(
                user.getUsername(),
                user.getRole().name(),
                TenantContext.getTenant()
        );

        // Reaching the control plane is worth a permanent record; a cashier signing into
        // their own till a hundred times a day is not, and would bury the trail.
        if (user.getRole() == Role.SUPER_ADMIN) {
            auditService.record(user.getUsername(), "LOGIN_SUCCESS",
                    SuperAdminAuditService.TARGET_SYSTEM, "AUTH",
                    "Super admin signed in");
        }

        String shopName = resolveShopName(user);

        return new AuthResponse(
                user.getId(),
                token,
                user.getUsername(),
                user.getRole().name(),
                user.getBranchId(),
                shopName,
                hasOfflinePin(user)
        );
    }

    /**
     * Every failure gets a log line; only the failure that trips the lock gets an audit row.
     * A brute-force run writes thousands of attempts, and one DB insert per attempt would let
     * the attack denial-of-service the control-plane database it is supposed to be reported in.
     */
    private void onFailedLogin(String tenantId, String username) {
        boolean justLocked = loginAttemptService.recordFailure(tenantId, username);
        log.warn("Failed login. tenant={}, username={}{}",
                tenantId, username, justLocked ? " — account now locked" : "");

        if (justLocked) {
            auditService.record(username, "LOGIN_LOCKED",
                    SuperAdminAuditService.TARGET_SHOP, tenantId,
                    "Locked " + username + " for " + loginAttemptService.lockoutMinutes()
                            + " minute(s) after repeated failed sign-ins");
        }
    }

    /**
     * Ends the session this request is authenticated with. Only this one: the caller's other
     * devices keep working, which is why it revokes a jti rather than bumping the watermark.
     */
    public void logout(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }
        try {
            tokenDenyList.revoke(jwtService.extractTokenId(bearerToken),
                    jwtService.extractExpiration(bearerToken));
        } catch (Exception exception) {
            // Already expired or malformed — there is nothing left to revoke, and a logout
            // must never fail in a way that leaves the client believing it is still signed in.
            log.debug("Logout called with a token that could not be parsed", exception);
        }
    }

    /**
     * Invalidates every token this user currently holds by moving their watermark to now.
     * Called after a password change: the point of resetting a compromised password is that
     * whoever had it stops having access, and a still-valid 24-hour token defeats that.
     */
    @Transactional
    public void revokeAllSessions(User user) {
        user.setTokenValidFrom(LocalDateTime.now());
        userRepository.save(user);
    }

    public User getLoggedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    public CurrentUserResponse getCurrentUser() {
        User user = getLoggedUser();
        String shopName = resolveShopName(user);

        return CurrentUserResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole().name())
                .branchId(user.getBranchId())
                .shopName(shopName)
                .hasOfflinePin(hasOfflinePin(user))
                .build();
    }

    public OfflinePinStatusResponse getOfflinePinStatus() {
        return new OfflinePinStatusResponse(hasOfflinePin(getLoggedUser()));
    }

    @Transactional
    public OfflinePinStatusResponse saveOfflinePin(OfflinePinRequest request) {
        User user = getLoggedUser();

        if (hasOfflinePin(user)) {
            if (request.getCurrentPin() == null || request.getCurrentPin().isBlank()) {
                throw new BadRequestException("Current PIN is required");
            }
            if (!passwordEncoder.matches(request.getCurrentPin(), user.getOfflinePinHash())) {
                throw new BadRequestException("Current PIN is incorrect");
            }
        }

        user.setOfflinePinHash(passwordEncoder.encode(request.getNewPin()));
        user.setOfflinePinUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return new OfflinePinStatusResponse(true);
    }

    private boolean hasOfflinePin(User user) {
        return user.getOfflinePinHash() != null && !user.getOfflinePinHash().isBlank();
    }

    private String resolveShopName(User user) {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equalsIgnoreCase(tenantId)) {
            return null;
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER",
                () -> tx.execute(status ->
                        tenantSubscriptionRepository.findByTenantId(tenantId)
                                .map(sub -> sub.getShopName())
                                .orElse(null)));
    }
}
