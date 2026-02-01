package com.chala.posapp.service;

import com.chala.posapp.dto.ExpenseResponse;
import com.chala.posapp.dto.CreateExpenseRequest;
import com.chala.posapp.entity.*;
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
    private final BranchRepository branchRepository; // Branch නම ගැනීමට

    @Transactional
    public ExpenseResponse addExpense(CreateExpenseRequest request) {
        User user = getLoggedUser();

        // 1. දැනට ලොග් වී සිටින user ගේ branch එකේ active shift එක බලනවා.
        // Shift එකක් නැති වුණත් Error එකක් Throw කරන්නේ නැත.
        CashShift activeShift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                user.getBranchId(), user.getId(), ShiftStatus.OPEN).orElse(null);

        // 2. Expense එක Build කිරීම.
        Expense expense = Expense.builder()
                .amount(request.getAmount())
                .category(request.getCategory())
                .description(request.getDescription().trim())
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .shiftId(activeShift != null ? activeShift.getId() : null) // 🔥 Shift එක නැත්නම් null.
                .createdAt(LocalDateTime.now())
                .build();

        Expense savedExpense = expenseRepository.save(expense);

        // 3. Shift එකක් තියෙනවා නම් විතරක් ඒකේ Totals update කරන්න.
        if (activeShift != null) {
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
        // 1. Database එකෙන් අදාළ නම හොයාගන්නා ආකාරය
        String cashierName = userRepository.findById(expense.getCashierUserId())
                .map(User::getUsername).orElse("Unknown");

        String branchName = branchRepository.findById(expense.getBranchId())
                .map(Branch::getName).orElse("Unknown");

        // 2. DTO එක Build කිරීම
        return ExpenseResponse.builder()
                .id(expense.getId())
                .amount(expense.getAmount())
                .category(expense.getCategory().name())
                .description(expense.getDescription())
                .branchId(expense.getBranchId())
                .branchName(branchName)      // ✅ එකතු කළා
                .shiftId(expense.getShiftId())
                .cashierId(expense.getCashierUserId())
                .cashierName(cashierName)    // ✅ එකතු කළා
                .createdAt(expense.getCreatedAt())
                .build();
    }

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

}