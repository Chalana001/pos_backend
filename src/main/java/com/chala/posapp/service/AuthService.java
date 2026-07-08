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
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
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
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isEnabled()) throw new BadRequestException("User disabled");

        String token = jwtService.generateToken(
                user.getUsername(),
                user.getRole().name(),
                TenantContext.getTenant()
        );

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
