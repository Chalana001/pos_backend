package com.chala.posapp.service;

import com.chala.posapp.dto.ExpenseResponse;
import com.chala.posapp.dto.CreateExpenseRequest;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final CashShiftRepository cashShiftRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public ExpenseResponse addExpense(CreateExpenseRequest request) {
        User user = getLoggedUser();

        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
            request.setBranchId(user.getBranchId());
        }

        CashShift activeShift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                request.getBranchId(), user.getId(), ShiftStatus.OPEN).orElse(null);

        if (activeShift == null && user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Cashiers must have an open shift to record expenses from the drawer.");
        }

        Expense expense = Expense.builder()
                .amount(request.getAmount())
                .category(request.getCategory())
                .description(request.getDescription().trim())
                .branchId(request.getBranchId())
                .cashierUserId(user.getId())
                .shiftId(activeShift != null ? activeShift.getId() : null)
                .createdAt(LocalDateTime.now())
                .build();

        Expense savedExpense = expenseRepository.save(expense);

        if (request.isFromDrawer() && activeShift != null) {
            activeShift.setTotalExpenses(activeShift.getTotalExpenses() + request.getAmount());
            cashShiftRepository.save(activeShift);
        }
        return mapToResponse(savedExpense);
    }

    public List<ExpenseResponse> getExpenses(Long branchId, Instant from, Instant to) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime f = LocalDateTime.ofInstant(from, zone);
        LocalDateTime t = LocalDateTime.ofInstant(to, zone);

        List<Expense> expenses;
        if (branchId == null || branchId == 0) {
            expenses = expenseRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(f, t);
        } else {
            expenses = expenseRepository.findByBranchIdAndCreatedAtBetweenOrderByCreatedAtDesc(branchId, f, t);
        }

        return expenses.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private ExpenseResponse mapToResponse(Expense expense) {

        String cashierName = userRepository.findById(expense.getCashierUserId())
                .map(User::getUsername).orElse("Unknown");

        String branchName = branchRepository.findById(expense.getBranchId())
                .map(Branch::getName).orElse("Unknown");

        return ExpenseResponse.builder()
                .id(expense.getId())
                .amount(expense.getAmount())
                .category(expense.getCategory().name())
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

}