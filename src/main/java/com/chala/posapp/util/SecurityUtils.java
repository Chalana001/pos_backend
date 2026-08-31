package com.chala.posapp.util;

import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * BUG-07 / BUG-08 FIX: Centralised security helper.
 *
 * Previously getLoggedUser() (3-line method) and isAdminLike() (2-line method)
 * were copy-pasted across 15+ service classes. Any change had to be made in
 * 15+ places simultaneously. Now there is ONE source of truth.
 *
 * Usage in services:
 *   private final SecurityUtils securityUtils;
 *   ...
 *   User user = securityUtils.getCurrentUser();
 *   boolean admin = securityUtils.isAdminLike(user);
 */
@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private final UserRepository userRepository;

    /**
     * Returns the currently authenticated User entity.
     * Throws ResourceNotFoundException if the user is not found in the database.
     */
    public User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * Returns true if the user has ADMIN or SUPER_ADMIN role.
     * These roles can access cross-branch data and perform privileged operations.
     */
    public boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    /**
     * Convenience overload — resolves the current user internally.
     */
    public boolean currentUserIsAdminLike() {
        return isAdminLike(getCurrentUser());
    }

    /**
     * Returns the current user, or null if there is no active authentication
     * (e.g. anonymous / public endpoints that call security-optional logic).
     */
    public User getOptionalCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    // ---------------------------------------------------------------
    // DUP-05 FIX: Branch access helpers — previously copy-pasted across 9+ services
    // ---------------------------------------------------------------

    /**
     * Returns the user's assigned branchId, throwing NotAssignedException if null.
     * Previously copy-pasted as requireAssignedBranch() in 9 service classes.
     */
    public Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        return user.getBranchId();
    }

    /**
     * For stock/adjustment endpoints: admins pass through with their requested branchId;
     * non-admins are locked to their own branch. Handles null/0 branchId as "use own branch".
     * Previously copy-pasted as enforceBranchAccess() in StockService, StockAdjustmentService,
     * StockProcessingService.
     */
    public Long enforceBranchAccess(Long requestedBranchId) {
        User user = getCurrentUser();
        if (isAdminLike(user)) {
            return requestedBranchId;
        }
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        if (requestedBranchId == null || requestedBranchId == 0L) {
            return user.getBranchId();
        }
        if (!user.getBranchId().equals(requestedBranchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
        return requestedBranchId;
    }
}
