package com.chala.posapp.service;

import com.chala.posapp.dto.CashDropResponse;
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

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CashDropService {

    private final CashDropRepository cashDropRepository;
    private final UserRepository userRepository;
    private final ShiftService shiftService;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public Page<CashDropResponse> getFilteredCashDrops(Long requestedBranchId, Long requestedShiftId, LocalDateTime from, LocalDateTime to, Pageable pageable) {

        User currentUser = getLoggedUser();

        Long finalBranchId = requestedBranchId;
        Long finalShiftId = requestedShiftId;

        if (currentUser.getRole() == Role.CASHIER) {
            finalBranchId = requireAssignedBranch(currentUser);
            finalShiftId = shiftService.getMyCurrentShift().getId();
        } else if (currentUser.getRole() == Role.MANAGER) {
            finalBranchId = requireAssignedBranch(currentUser);
        }

        Page<CashDrop> drops = cashDropRepository.findWithFilters(finalBranchId, finalShiftId, from, to, pageable);

        return drops.map(this::mapToResponseDTO);
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
}
