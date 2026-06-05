package com.chala.posapp.service;

import com.chala.posapp.dto.ExpenseResponse;
import com.chala.posapp.dto.CreateExpenseRequest;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
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
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseTypeRepository expenseTypeRepository;
    private final CashShiftRepository cashShiftRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public ExpenseResponse addExpense(CreateExpenseRequest request) {
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, request.getBranchId());

        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found");
        }

        CashShift activeShift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                branchId, user.getId(), ShiftStatus.OPEN).orElse(null);

        boolean requiresOpenShift = request.isFromDrawer() || user.getRole() == Role.CASHIER;
        if (requiresOpenShift && activeShift == null) {
            throw new BadRequestException("An open shift is required to record a drawer expense.");
        }

        ExpenseType expenseType = expenseTypeRepository.findById(request.getExpenseTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense type not found"));
        if (!expenseType.isActive()) {
            throw new BadRequestException("Selected expense type is inactive");
        }

        Expense expense = Expense.builder()
                .amount(request.getAmount())
                .expenseTypeId(expenseType.getId())
                .category(expenseType.getName())
                .countInProfitReport(expenseType.isCountInProfitReport())
                .description(request.getDescription().trim())
                .branchId(branchId)
                .cashierUserId(user.getId())
                .shiftId(activeShift != null ? activeShift.getId() : null)
                .createdAt(LocalDateTime.now())
                .build();

        Expense savedExpense = expenseRepository.save(expense);

        if (request.isFromDrawer()) {
            activeShift.setTotalExpenses(activeShift.getTotalExpenses() + request.getAmount());
            cashShiftRepository.save(activeShift);
        }
        return mapToResponse(savedExpense);
    }

    public Page<ExpenseResponse> getFilteredExpenses(
            Long requestedBranchId,
            Long requestedCashierId,
            Long expenseTypeId,
            Long shiftId,
            String search,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    ) {

        User currentUser = getLoggedUser();
        Long finalBranchId = requestedBranchId;
        Long cashierUserId = requestedCashierId;

        if (currentUser.getRole() == Role.CASHIER) {
            finalBranchId = requireAssignedBranch(currentUser);
            cashierUserId = currentUser.getId();
        } else if (currentUser.getRole() == Role.MANAGER) {
            finalBranchId = requireAssignedBranch(currentUser);
        }

        String trimmedSearch = search == null || search.isBlank() ? null : search.trim();
        Page<Expense> expenses = expenseRepository.findWithFilters(
                finalBranchId,
                cashierUserId,
                expenseTypeId,
                shiftId,
                trimmedSearch,
                from,
                to,
                pageable
        );

        return expenses.map(this::mapToResponse);
    }

    public Page<ExpenseResponse> getShiftExpenses(Long shiftId, Pageable pageable) {
        User currentUser = getLoggedUser();
        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (currentUser.getRole() == Role.CASHIER) {
            if (!shift.getCashierUserId().equals(currentUser.getId())) {
                throw new BadRequestException("Not allowed");
            }
        } else if (currentUser.getRole() == Role.MANAGER) {
            Long managerBranchId = requireAssignedBranch(currentUser);
            if (!managerBranchId.equals(shift.getBranchId())) {
                throw new BadRequestException("Manager can only access their branch");
            }
        }

        return expenseRepository.findByShiftId(shiftId, pageable).map(this::mapToResponse);
    }

    private ExpenseResponse mapToResponse(Expense expense) {

        String cashierName = userRepository.findById(expense.getCashierUserId())
                .map(User::getUsername).orElse("Unknown");

        String branchName = branchRepository.findById(expense.getBranchId())
                .map(Branch::getName).orElse("Unknown");

        return ExpenseResponse.builder()
                .id(expense.getId())
                .amount(expense.getAmount())
                .expenseTypeId(expense.getExpenseTypeId())
                .category(expense.getCategory())
                .countInProfitReport(expense.isCountInProfitReport())
                .description(expense.getDescription())
                .branchId(expense.getBranchId())
                .branchName(branchName)
                .shiftId(expense.getShiftId())
                .cashierId(expense.getCashierUserId())
                .cashierName(cashierName)
                .createdAt(expense.getCreatedAt())
                .build();
    }

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.ADMIN) {
            if (requestedBranchId == null || requestedBranchId == 0) {
                throw new BadRequestException("Branch is required");
            }
            return requestedBranchId;
        }

        return requireAssignedBranch(user);
    }

    private Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        return user.getBranchId();
    }

}
