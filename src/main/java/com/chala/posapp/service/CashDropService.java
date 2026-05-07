package com.chala.posapp.service;

import com.chala.posapp.dto.CashDropResponse;
import com.chala.posapp.dto.CashDropSummaryResponse;
import com.chala.posapp.entity.CashDrop;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CashDropRepository;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashDropService {

    private final CashDropRepository cashDropRepository;
    private final UserRepository userRepository;
    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public Page<CashDropResponse> getFilteredCashDrops(
            Long requestedBranchId,
            Long requestedShiftId,
            Long requestedCashierUserId,
            String search,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    ) {
        CashDropFilterScope scope = resolveScope(requestedBranchId, requestedShiftId, requestedCashierUserId, search);
        Page<CashDrop> drops = cashDropRepository.findWithFilters(
                scope.branchId(),
                scope.shiftId(),
                scope.cashierUserId(),
                scope.search(),
                from,
                to,
                pageable
        );

        return drops.map(this::mapToResponseDTO);
    }

    public CashDropSummaryResponse getCashDropSummary(
            Long requestedBranchId,
            Long requestedShiftId,
            Long requestedCashierUserId,
            String search,
            LocalDateTime from,
            LocalDateTime to
    ) {
        CashDropFilterScope scope = resolveScope(requestedBranchId, requestedShiftId, requestedCashierUserId, search);
        CashDropSummaryResponse summary = cashDropRepository.summarizeWithFilters(
                scope.branchId(),
                scope.shiftId(),
                scope.cashierUserId(),
                scope.search(),
                from,
                to
        );
        return summary == null ? new CashDropSummaryResponse(0, 0, 0) : summary;
    }

    private CashDropFilterScope resolveScope(Long requestedBranchId, Long requestedShiftId, Long requestedCashierUserId, String search) {
        User currentUser = getLoggedUser();
        Long finalBranchId = requestedBranchId;
        Long finalShiftId = requestedShiftId;
        Long finalCashierUserId = requestedCashierUserId;
        String trimmedSearch = search == null || search.isBlank() ? null : search.trim();

        if (currentUser.getRole() == Role.CASHIER) {
            finalBranchId = requireAssignedBranch(currentUser);
            finalCashierUserId = currentUser.getId();
        } else if (currentUser.getRole() == Role.MANAGER) {
            finalBranchId = requireAssignedBranch(currentUser);
        }

        return new CashDropFilterScope(finalBranchId, finalShiftId, finalCashierUserId, trimmedSearch);
    }

    private CashDropResponse mapToResponseDTO(CashDrop cashDrop) {

        String cashierName = userRepository.findById(cashDrop.getCashierUserId())
                .map(User::getUsername)
                .orElse("Unknown Cashier");

        return CashDropResponse.builder()
                .id(cashDrop.getId())
                .shiftId(cashDrop.getShiftId())
                .branchId(cashDrop.getBranchId())
                .cashierUserId(cashDrop.getCashierUserId())
                .cashierName(cashierName)
                .amount(cashDrop.getAmount())
                .reason(cashDrop.getReason())
                .createdAt(cashDrop.getCreatedAt())
                .build();
    }

    private Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        return user.getBranchId();
    }

    private record CashDropFilterScope(Long branchId, Long shiftId, Long cashierUserId, String search) {
    }
}
