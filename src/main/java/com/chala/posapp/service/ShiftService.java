package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.dto.shift.CloseShiftRequest;
import com.chala.posapp.dto.shift.OpenShiftRequest;
import com.chala.posapp.dto.shift.ShiftResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private final CashShiftRepository cashShiftRepository;
    private final ExpenseRepository expenseRepository;
    private final CashDropRepository cashDropRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final BranchRepository branchRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private CashShift getOpenShiftOrThrow(Long branchId, Long cashierId) {
        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }
        if (!userRepository.existsById(cashierId)) {
            throw new ResourceNotFoundException("User not found in the system");
        }

        return cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, cashierId, ShiftStatus.OPEN)
                .orElseThrow(() -> new ResourceNotFoundException("No open shift found"));
    }

    @Transactional
    public ShiftResponse openShift(OpenShiftRequest request) {
        User user = getLoggedUser();

        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        if (user.getRole() != Role.CASHIER && user.getRole() != Role.MANAGER && user.getRole() != Role.ADMIN)
            throw new RuntimeException("Not allowed");

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(user.getBranchId(), user.getId(), ShiftStatus.OPEN)
                .ifPresent(s -> {
                    throw new AlreadyExistsException("Shift already open");
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
        if (user.getRole() != Role.ADMIN && user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        CashShift shift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                        user.getBranchId(),
                        user.getId(),
                        ShiftStatus.OPEN
                )
                .orElseThrow(() -> new ResourceNotFoundException("No active shift found"));
        System.out.println(shift);
        return map(shift);
    }

    @Transactional
    public ShiftResponse addExpense(CreateExpenseRequest request) {
        User user = getLoggedUser();
        if (user.getBranchId() == null) throw new NotAssignedException("User branch not assigned");

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
        if (user.getBranchId() == null) throw new NotAssignedException("User branch not assigned");

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
    public ShiftResponse closeShiftById(Long shiftId, CloseShiftRequest request) {

        if (shiftId == null) {
            throw new BadRequestException("Shift ID cannot be null");
        }

        User user = getLoggedUser();

        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
            throw new RuntimeException("Not allowed");
        }

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new AlreadyExistsException("Shift already closed");
        }

        Double calculatedSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
                OrderType.CASH,
                OrderStatus.COMPLETED,
                shift.getOpenedAt(),
                LocalDateTime.now()
        );

        // Null check and fallback to 0.0
        double safeCashSales = (calculatedSales == null) ? 0.0 : calculatedSales;

        shift.setCashSales(safeCashSales);

        Double expected = shift.getOpeningCash()
                + safeCashSales
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
    public ShiftResponse closeShift(CloseShiftRequest request) {
        User user = getLoggedUser();
        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        Double calculatedSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
                OrderType.CASH,
                OrderStatus.COMPLETED,
                shift.getOpenedAt(),
                LocalDateTime.now()
        );

        // Null check and fallback to 0.0
        double safeCashSales = (calculatedSales == null) ? 0.0 : calculatedSales;

        shift.setCashSales(safeCashSales);

        Double expected = shift.getOpeningCash()
                + safeCashSales
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

//    @Transactional
//    public ShiftResponse closeShift(CloseShiftRequest request) {
//        User user = getLoggedUser();
//        if (user.getBranchId() == null)
//            throw new NotAssignedException("User branch not assigned");
//
//        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());
//
//        Double cashSales = orderRepository.sumCashSales(
//                shift.getBranchId(),
//                shift.getCashierUserId(),
//                OrderType.CASH,
//                OrderStatus.COMPLETED,
//                shift.getOpenedAt(),
//                LocalDateTime.now()
//        );
//
//        shift.setCashSales(cashSales);
//
//        Double expected = shift.getOpeningCash()
//                + cashSales
//                - shift.getTotalExpenses()
//                - shift.getTotalCashDrops();
//
//        shift.setExpectedCash(expected);
//        shift.setCountedCash(request.getCountedCash());
//        shift.setCashDifference(request.getCountedCash() - expected);
//        shift.setStatus(ShiftStatus.CLOSED);
//        shift.setCloseNote(request.getNote());
//        shift.setClosedAt(LocalDateTime.now());
//
//        return map(cashShiftRepository.save(shift));
//    }

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
    public List<ShiftResponse> getAllActiveShiftsByBranch(Long branchId) {

        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }

        List<CashShift> shifts = cashShiftRepository.findAllByBranchIdAndStatus(branchId, ShiftStatus.OPEN);

        return shifts.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }

    public List<ShiftResponse> getAllShifts(Long branchId, Long cashierId, LocalDateTime start, LocalDateTime end, ShiftStatus status) {
        return cashShiftRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (branchId != null) predicates.add(cb.equal(root.get("branchId"), branchId));
            if (cashierId != null) predicates.add(cb.equal(root.get("cashierUserId"), cashierId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));

            if (start != null && end != null) {
                predicates.add(cb.between(root.get("openedAt"), start, end));
            }

            query.orderBy(cb.desc(root.get("openedAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        }).stream().map(this::map).collect(Collectors.toList());
    }

//    @Transactional
//    public ShiftResponse closeShiftById(Long shiftId, CloseShiftRequest request) {
//
//        if (shiftId == null) {
//            throw new BadRequestException("Shift ID cannot be null");
//        }
//
//        User user = getLoggedUser();
//
//        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
//            throw new RuntimeException("Not allowed");
//        }
//
//        CashShift shift = cashShiftRepository.findById(shiftId)
//                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
//
//        if (shift.getStatus() != ShiftStatus.OPEN) {
//            throw new AlreadyExistsException("Shift already closed");
//        }
//
//        Double cashSales = orderRepository.sumCashSales(
//                shift.getBranchId(),
//                shift.getCashierUserId(),
//                OrderType.CASH,
//                OrderStatus.COMPLETED,
//                shift.getOpenedAt(),
//                LocalDateTime.now()
//        );
//
//        shift.setCashSales(cashSales);
//
//        Double expected = shift.getOpeningCash()
//                + cashSales
//                - shift.getTotalExpenses()
//                - shift.getTotalCashDrops();
//
//        shift.setExpectedCash(expected);
//        shift.setCountedCash(request.getCountedCash());
//        shift.setCashDifference(request.getCountedCash() - expected);
//        shift.setStatus(ShiftStatus.CLOSED);
//        shift.setCloseNote(request.getNote());
//        shift.setClosedAt(LocalDateTime.now());
//
//        return map(cashShiftRepository.save(shift));
//    }
    @Transactional
    public ShiftResponse openShiftByBranch(Long branchId, OpenShiftRequest request) {

        User user = getLoggedUser();

        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (request.getAssignedCashierId() == null) {
            throw new BadRequestException("Assigned Cashier ID is required");
        }

        if (request.getAssignedCashierId() == 0) {
            request.setAssignedCashierId(user.getId());
        }else {
            User cashier = userRepository.findById(request.getAssignedCashierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cashier not found"));

            if (!branchId.equals(cashier.getBranchId())){
                throw new ResourceNotFoundException("This cashier is not in this branch");
            }
        }

        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, request.getAssignedCashierId(), ShiftStatus.OPEN)
                .ifPresent(s -> {
                    throw new AlreadyExistsException("This cashier already has an open shift in this branch");
                });

        CashShift shift = CashShift.builder()
                .branchId(branchId)
                .cashierUserId(request.getAssignedCashierId())
                .status(ShiftStatus.OPEN)
                .openingCash(request.getOpeningCash())
                .openNote(request.getNote())
                .build();

        return map(cashShiftRepository.save(shift));
    }

    @Transactional
    public ShiftResponse addExpenseByShiftId(Long shiftId, CreateExpenseRequest request) {

        if (shiftId == null) {
            throw new BadRequestException("Shift ID cannot be null");
        }

        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new RuntimeException("Not allowed");

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new BadRequestException("Shift is closed");

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
        if (shiftId == null) {
            throw new BadRequestException("Shift ID cannot be null");
        }
        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new BadRequestException("Not allowed");

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new BadRequestException("Shift is closed");

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
