package com.chala.posapp.service;

import com.chala.posapp.entity.User;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.rbac.TenantOwnershipException;
import com.chala.posapp.rbac.TenantOwnershipState;
import com.chala.posapp.rbac.TenantRbacJdbcService;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TenantOwnershipService {
    private final TenantRbacJdbcService rbacJdbcService;
    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final com.chala.posapp.rbac.TenantReauthenticationService reauthenticationService;

    public boolean canManageOwnership(User user) {
        return user != null && rbacJdbcService.isOwner(user.getId());
    }

    public TenantOwnershipState current() {
        User actor = securityUtils.getCurrentUser();
        TenantOwnershipState state = rbacJdbcService.ownership();
        if (state.resolved() && !state.ownerUserId().equals(actor.getId())) {
            return new TenantOwnershipState(null, null, state.status(), state.version());
        }
        return state;
    }

    public TenantOwnershipState currentForAuthorizedRead() {
        return rbacJdbcService.ownership();
    }

    public TenantOwnershipState reconcileVerifiedOnboarding(String onboardingUsername, Long actorUserId) {
        return rbacJdbcService.resolveFromVerifiedOnboarding(onboardingUsername, actorUserId);
    }

    public TenantOwnershipState resolveForInitialOnboarding(String onboardingUsername, Long createdUserId) {
        User created = userRepository.findById(createdUserId)
                .orElseThrow(() -> new TenantOwnershipException("Created onboarding user was not found"));
        if (!created.isEnabled() || !created.getUsername().equals(onboardingUsername)) {
            throw new TenantOwnershipException("Onboarding ownership provenance could not be verified");
        }
        return rbacJdbcService.resolveFromVerifiedOnboarding(created.getUsername(), created.getId());
    }

    public TenantOwnershipState transfer(Long targetUserId, long expectedVersion, String reauthenticationChallenge) {
        User actor = securityUtils.getCurrentUser();
        TenantOwnershipState before = current();
        try {
            reauthenticationService.consumeOwnerTransferChallenge(reauthenticationChallenge);
            if (before.ownerUserId() == null || !before.ownerUserId().equals(actor.getId())) {
                throw new TenantOwnershipException("Only the current tenant owner can transfer ownership");
            }
            User target = userRepository.findById(targetUserId)
                    .orElse(null);
            if (target == null) {
                throw new TenantOwnershipException("Target user not found");
            }
            if (!target.isEnabled()) {
                throw new TenantOwnershipException("Disabled users cannot become tenant owners");
            }
            if (actor.getId().equals(target.getId())) {
                throw new TenantOwnershipException("Ownership transfer target must be different");
            }
            TenantOwnershipState after = rbacJdbcService.transfer(actor.getId(), target.getId(), expectedVersion);
            authService.revokeAllSessions(actor);
            authService.revokeAllSessions(target);
            return after;
        } catch (RuntimeException ex) {
            try {
                rbacJdbcService.writeAuditOutsideTransaction(actor.getId(), "OWNER_TRANSFER_REJECTED",
                        before.ownerUserId(), targetUserId, "REJECTED", ex.getMessage());
            } catch (RuntimeException auditFailure) {
                throw new ConflictException("Ownership transfer rejected and durable audit failed");
            }
            if (ex instanceof TenantOwnershipException) {
                throw new ConflictException(ex.getMessage());
            }
            throw ex;
        }
    }

    public boolean isOwner(Long userId) {
        return rbacJdbcService.isOwner(userId);
    }

}
