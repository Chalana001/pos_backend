package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import jakarta.persistence.Column;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private final CashShiftRepository cashShiftRepository;
    private final ExpenseRepository expenseRepository;
    private final CashDropRepository cashDropRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private CashShift getOpenShiftOrThrow(Long branchId, Long cashierId) {
        return cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, cashierId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("No open shift found"));
    }

    @Transactional
    public ShiftResponse openShift(OpenShiftRequest request) {
        User user = getLoggedUser();

        if (user.getBranchId() == null)
            throw new RuntimeException("User branch not assigned");

        if (user.getRole() != Role.CASHIER && user.getRole() != Role.MANAGER && user.getRole() != Role.ADMIN)
            throw new RuntimeException("Not allowed");

        // prevent double open shift
        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(user.getBranchId(), user.getId(), ShiftStatus.OPEN)
                .ifPresent(s -> {
                    throw new RuntimeException("Shift already open");
                });

        CashShift shift = CashShift.builder()
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .status(ShiftStatus.OPEN)
                .openingCash(request.getOpeningCash())
                .openNote(request.getNote())
                .build();

        return map(cashShiftRepository.save(shift));
    }

    public ShiftResponse getMyCurrentShift() {
        User user = getLoggedUser();
        if (user.getBranchId() == null)
            throw new RuntimeException("User branch not assigned");

        CashShift shift = cashShiftRepository.findTopByBranchIdAndCashierUserIdOrderByOpenedAtDesc(user.getBranchId(), user.getId())
                .orElseThrow(() -> new RuntimeException("No shift found"));

        return map(shift);
    }

    @Transactional
    public ShiftResponse addExpense(CreateExpenseRequest request) {
        User user = getLoggedUser();
        if (user.getBranchId() == null) throw new RuntimeException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        Expense expense = Expense.builder()
                .shiftId(shift.getId())
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .category(request.getCategory())
                .amount(request.getAmount())
                .description(request.getDescription().trim())
                .build();

        expenseRepository.save(expense);

        shift.setTotalExpenses(shift.getTotalExpenses() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }

    @Transactional
    public ShiftResponse addCashDrop(CreateCashDropRequest request) {
        User user = getLoggedUser();
        if (user.getBranchId() == null) throw new RuntimeException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        CashDrop cashDrop = CashDrop.builder()
                .shiftId(shift.getId())
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .reason(request.getReason().trim())
                .build();

        cashDropRepository.save(cashDrop);

        shift.setTotalCashDrops(shift.getTotalCashDrops() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }

    @Transactional
    public ShiftResponse closeShift(CloseShiftRequest request) {
        User user = getLoggedUser();
        if (user.getBranchId() == null)
            throw new RuntimeException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        // ✅ Calculate CASH sales during shift
        double cashSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
                OrderType.CASH,
                OrderStatus.COMPLETED,
                shift.getOpenedAt(),
                LocalDateTime.now()
        );

        shift.setCashSales(cashSales);

        // ✅ Expected cash calculation (FINAL)
        double expected = shift.getOpeningCash()
                + cashSales
                - shift.getTotalExpenses()
                - shift.getTotalCashDrops();

        shift.setExpectedCash(expected);
        shift.setCountedCash(request.getCountedCash());
        shift.setCashDifference(request.getCountedCash() - expected);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setCloseNote(request.getNote());
        shift.setClosedAt(LocalDateTime.now());

        return map(cashShiftRepository.save(shift));
    }


    private ShiftResponse map(CashShift s) {
        return ShiftResponse.builder()
                .id(s.getId())
                .branchId(s.getBranchId())
                .cashierUserId(s.getCashierUserId())
                .status(s.getStatus())
                .openingCash(s.getOpeningCash())
                .totalExpenses(s.getTotalExpenses())
                .totalCashDrops(s.getTotalCashDrops())
                .cashSales(s.getCashSales())
                .expectedCash(s.getExpectedCash())
                .countedCash(s.getCountedCash())
                .cashDifference(s.getCashDifference())
                .openNote(s.getOpenNote())
                .closeNote(s.getCloseNote())
                .openedAt(s.getOpenedAt())
                .closedAt(s.getClosedAt())
                .build();
    }
    public ShiftResponse getActiveShiftByBranch(Long branchId) {
        CashShift shift = cashShiftRepository
                .findFirstByBranchIdAndStatus(branchId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("No active shift for this branch"));

        return map(shift);
    }

    @Transactional
    public ShiftResponse closeShiftById(Long shiftId, CloseShiftRequest request) {
        User user = getLoggedUser();

        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
            throw new RuntimeException("Not allowed");
        }

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new RuntimeException("Shift already closed");
        }

        // ✅ Calculate CASH sales during shift
        double cashSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
                OrderType.CASH,
                OrderStatus.COMPLETED,
                shift.getOpenedAt(),
                LocalDateTime.now()
        );

        shift.setCashSales(cashSales);

        double expected = shift.getOpeningCash()
                + cashSales
                - shift.getTotalExpenses()
                - shift.getTotalCashDrops();

        shift.setExpectedCash(expected);
        shift.setCountedCash(request.getCountedCash());
        shift.setCashDifference(request.getCountedCash() - expected);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setCloseNote(request.getNote());
        shift.setClosedAt(LocalDateTime.now());

        return map(cashShiftRepository.save(shift));
    }
    @Transactional
    public ShiftResponse openShiftByBranch(Long branchId, OpenShiftRequest request) {
        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new RuntimeException("Not allowed");

        cashShiftRepository.findFirstByBranchIdAndStatus(branchId, ShiftStatus.OPEN)
                .ifPresent(s -> { throw new RuntimeException("Shift already open for this branch"); });

        CashShift shift = CashShift.builder()
                .branchId(branchId)
                .cashierUserId(user.getId()) // opened by admin
                .status(ShiftStatus.OPEN)
                .openingCash(request.getOpeningCash())
                .openNote(request.getNote())
                .build();

        return map(cashShiftRepository.save(shift));
    }
    @Transactional
    public ShiftResponse addExpenseByShiftId(Long shiftId, CreateExpenseRequest request) {
        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new RuntimeException("Not allowed");

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new RuntimeException("Shift is closed");

        Expense expense = Expense.builder()
                .shiftId(shift.getId())
                .branchId(shift.getBranchId())
                .cashierUserId(user.getId())
                .category(request.getCategory())
                .amount(request.getAmount())
                .description(request.getDescription().trim())
                .build();

        expenseRepository.save(expense);

        shift.setTotalExpenses(shift.getTotalExpenses() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }
    @Transactional
    public ShiftResponse addCashDropByShiftId(Long shiftId, CreateCashDropRequest request) {
        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new RuntimeException("Not allowed");

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new RuntimeException("Shift is closed");

        CashDrop cashDrop = CashDrop.builder()
                .shiftId(shift.getId())
                .branchId(shift.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .reason(request.getReason().trim())
                .build();

        cashDropRepository.save(cashDrop);

        shift.setTotalCashDrops(shift.getTotalCashDrops() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }



}
