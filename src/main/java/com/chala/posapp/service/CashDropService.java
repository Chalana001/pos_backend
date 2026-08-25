package com.chala.posapp.service;

import com.chala.posapp.dto.CashDropResponse;
import com.chala.posapp.dto.CashDropSummaryResponse;
import com.chala.posapp.dto.RecordOutsideShiftCashDropRequest;
import com.chala.posapp.entity.BankAccount;
import com.chala.posapp.entity.CashDrop;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BankAccountRepository;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CashDropRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashDropService {

    private final CashDropRepository cashDropRepository;
    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BranchRepository branchRepository;
    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() — use SecurityUtils instead

    public Page<CashDropResponse> getFilteredCashDrops(
            Long requestedBranchId,
            Long requestedShiftId,
            Long requestedCashierUserId,
            Long bankAccountId,
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
                bankAccountId,
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
            Long bankAccountId,
            String search,
            LocalDateTime from,
            LocalDateTime to
    ) {
        CashDropFilterScope scope = resolveScope(requestedBranchId, requestedShiftId, requestedCashierUserId, search);
        CashDropSummaryResponse summary = cashDropRepository.summarizeWithFilters(
                scope.branchId(),
                scope.shiftId(),
                scope.cashierUserId(),
                bankAccountId,
                scope.search(),
                from,
                to
        );
        return summary == null ? new CashDropSummaryResponse(0, 0, 0) : summary;
    }

    // A drop recorded outside any shift — e.g. an owner banking
    // already-collected cash after every shift for the day is closed.
    // Deliberately does NOT touch any CashShift row: it's pure
    // record-keeping, never subtracted from a shift's Expected Cash.
    @Transactional
    public CashDropResponse addOutsideShiftDrop(RecordOutsideShiftCashDropRequest request) {
        User user = securityUtils.getCurrentUser();
        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (!branchRepository.existsById(request.getBranchId())) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }
        if (user.getRole() == Role.MANAGER) {
            Long managerBranchId = securityUtils.requireAssignedBranch(user);
            if (!managerBranchId.equals(request.getBranchId())) {
                throw new BadRequestException("Manager can only record drops for their own branch");
            }
        }

        BankAccount bankAccount = resolveActiveBankAccount(request.getBankAccountId());

        CashDrop drop = CashDrop.builder()
                .shiftId(null)
                .branchId(request.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .reason(request.getReason().trim())
                .bankAccountId(bankAccount != null ? bankAccount.getId() : null)
                .build();

        return mapToResponseDTO(cashDropRepository.save(drop));
    }

    private BankAccount resolveActiveBankAccount(Long bankAccountId) {
        if (bankAccountId == null) {
            return null;
        }
        BankAccount bankAccount = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
        if (!bankAccount.isActive()) {
            throw new BadRequestException("This bank account is inactive");
        }
        return bankAccount;
    }

    private CashDropFilterScope resolveScope(Long requestedBranchId, Long requestedShiftId, Long requestedCashierUserId, String search) {
        User currentUser = securityUtils.getCurrentUser();
        Long finalBranchId = requestedBranchId;
        Long finalShiftId = requestedShiftId;
        Long finalCashierUserId = requestedCashierUserId;
        String trimmedSearch = search == null || search.isBlank() ? null : search.trim();

        if (currentUser.getRole() == Role.CASHIER) {
            finalBranchId = securityUtils.requireAssignedBranch(currentUser);
            finalCashierUserId = currentUser.getId();
        } else if (currentUser.getRole() == Role.MANAGER) {
            finalBranchId = securityUtils.requireAssignedBranch(currentUser);
        }

        return new CashDropFilterScope(finalBranchId, finalShiftId, finalCashierUserId, trimmedSearch);
    }

    private CashDropResponse mapToResponseDTO(CashDrop cashDrop) {

        String cashierName = userRepository.findById(cashDrop.getCashierUserId())
                .map(User::getUsername)
                .orElse("Unknown Cashier");

        String bankAccountName = cashDrop.getBankAccountId() == null
                ? null
                : bankAccountRepository.findById(cashDrop.getBankAccountId())
                        .map(BankAccount::getName)
                        .orElse("Unknown Account");

        return CashDropResponse.builder()
                .id(cashDrop.getId())
                .shiftId(cashDrop.getShiftId())
                .branchId(cashDrop.getBranchId())
                .cashierUserId(cashDrop.getCashierUserId())
                .cashierName(cashierName)
                .amount(cashDrop.getAmount())
                .reason(cashDrop.getReason())
                .bankAccountId(cashDrop.getBankAccountId())
                .bankAccountName(bankAccountName)
                .outsideShift(cashDrop.getShiftId() == null)
                .createdAt(cashDrop.getCreatedAt())
                .build();
    }

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private record CashDropFilterScope(Long branchId, Long shiftId, Long cashierUserId, String search) {
    }
}
