package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.dto.shift.CloseShiftRequest;
import com.chala.posapp.dto.shift.OpenShiftRequest;
import com.chala.posapp.dto.shift.ShiftResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.util.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftService {

    private final CashShiftRepository cashShiftRepository;
    private final ExpenseRepository expenseRepository;
    private final CashDropRepository cashDropRepository;
    private final SecurityUtils securityUtils;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final BranchRepository branchRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final PurchaseRepository purchaseRepository;
    private final BankAccountRepository bankAccountRepository;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureManagerBranchAccess(User user, Long branchId) {
        if (user.getRole() != Role.MANAGER) {
            return;
        }

        Long userBranchId = securityUtils.requireAssignedBranch(user);
        if (!userBranchId.equals(branchId)) {
            throw new BadRequestException("Manager can only access their branch");
        }
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
        User user = securityUtils.getCurrentUser();

        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        if (user.getRole() != Role.CASHIER && user.getRole() != Role.MANAGER && !securityUtils.isAdminLike(user))
            throw new BadRequestException("Not allowed");

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

        return map(persistNewShift(shift, "Shift already open"));
    }

    /**
     * Saves a new OPEN shift and flushes immediately so that the
     * uk_cash_shifts_open_lock unique index (migration V27) is evaluated here,
     * inside this try block, rather than at commit time.
     *
     * The findBy...(status = OPEN) check above is a check-then-insert: two
     * requests fired milliseconds apart (double-click) can both pass it before
     * either one inserts. The unique index is what actually prevents the second
     * row; this method just turns the resulting constraint violation back into
     * the same friendly 409 the check-then-insert path produces.
     */
    private CashShift persistNewShift(CashShift shift, String duplicateMessage) {
        try {
            return cashShiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyExistsException(duplicateMessage);
        }
    }

    public ShiftResponse getMyCurrentShift() {
        User user = securityUtils.getCurrentUser();
        // FIX (Bug #17): Admin with no branch assigned would cause NPE when branchId is null
        // and is passed to findByBranchIdAndCashierUserIdAndStatus.
        // Admins should use getAdminShift(branchId) instead.
        if (securityUtils.isAdminLike(user)) {
            throw new BadRequestException("Admin users should use the branch-scoped shift endpoint. Use /api/shifts/admin?branchId=<id>");
        }
        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        CashShift shift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                        user.getBranchId(),
                        user.getId(),
                        ShiftStatus.OPEN
                )
                .orElseThrow(() -> new ResourceNotFoundException("No active shift found"));
        return map(shift);
    }

    @Transactional(readOnly = true)
    public ShiftResponse getAdminShift(Long branchId) {
        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed: Only Admins/Managers can use this method");
        }
        if (branchId == null || branchId == 0) {
            throw new BadRequestException("Please select a branch first");
        }
        ensureManagerBranchAccess(user, branchId);
        CashShift shift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                branchId,
                user.getId(),
                ShiftStatus.OPEN
        ).orElseThrow(() -> new ResourceNotFoundException("No active shift found for you in this branch"));
        return map(shift);
    }

//    @Transactional
//    public ShiftResponse addExpense(CreateExpenseRequest request) {
//        User user = securityUtils.getCurrentUser();
//        if (user.getBranchId() == null) throw new NotAssignedException("User branch not assigned");
//
//        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());
//
//        Expense expense = Expense.builder()
//                .shiftId(shift.getId())
//                .branchId(user.getBranchId())
//                .cashierUserId(user.getId())
//                .category(request.getCategory())
//                .amount(request.getAmount())
//                .description(request.getDescription().trim())
//                .build();
//
//        expenseRepository.save(expense);
//
//        shift.setTotalExpenses(shift.getTotalExpenses() + request.getAmount());
//        cashShiftRepository.save(shift);
//
//        return map(shift);
//    }

    @Transactional
    public ShiftResponse addCashDrop(CreateCashDropRequest request) {
        User user = securityUtils.getCurrentUser();
        if (user.getBranchId() == null) throw new NotAssignedException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        CashDrop cashDrop = CashDrop.builder()
                .shiftId(shift.getId())
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .reason(request.getReason().trim())
                .bankAccountId(resolveActiveBankAccountId(request.getBankAccountId()))
                .build();

        cashDropRepository.save(cashDrop);

        shift.setTotalCashDrops(shift.getTotalCashDrops() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }

    // Validates the bank account (if provided) exists and is still active —
    // returns null unchanged for "not banked yet, went to a safe".
    private Long resolveActiveBankAccountId(Long bankAccountId) {
        if (bankAccountId == null) {
            return null;
        }
        BankAccount bankAccount = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
        if (!bankAccount.isActive()) {
            throw new BadRequestException("This bank account is inactive");
        }
        return bankAccount.getId();
    }

    @Transactional
    public ShiftResponse closeShiftById(Long shiftId, CloseShiftRequest request) {

        if (shiftId == null) {
            throw new BadRequestException("Shift ID cannot be null");
        }

        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        ensureManagerBranchAccess(user, shift.getBranchId());

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new AlreadyExistsException("Shift already closed");
        }

        Double calculatedSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
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
        User user = securityUtils.getCurrentUser();
        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        CashShift shift = getOpenShiftOrThrow(user.getBranchId(), user.getId());

        Double calculatedSales = orderRepository.sumCashSales(
                shift.getBranchId(),
                shift.getCashierUserId(),
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
//        User user = securityUtils.getCurrentUser();
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

//    private ShiftResponse map(CashShift s) {
//        return ShiftResponse.builder()
//                .id(s.getId())
//                .branchId(s.getBranchId())
//                .cashierUserId(s.getCashierUserId())
//                .status(s.getStatus())
//                .openingCash(s.getOpeningCash())
//                .totalExpenses(s.getTotalExpenses())
//                .totalCashDrops(s.getTotalCashDrops())
//                .cashSales(s.getCashSales())
//                .expectedCash(s.getExpectedCash())
//                .countedCash(s.getCountedCash())
//                .cashDifference(s.getCashDifference())
//                .openNote(s.getOpenNote())
//                .closeNote(s.getCloseNote())
//                .openedAt(s.getOpenedAt())
//                .closedAt(s.getClosedAt())
//                .build();
//    }

    public List<ShiftResponse> getAllActiveShiftsByBranch(Long branchId) {
        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (branchId == null || branchId == 0) {
            if (user.getRole() == Role.MANAGER) {
                branchId = securityUtils.requireAssignedBranch(user);
            } else {
            List<CashShift> allActiveShifts = cashShiftRepository.findAllByStatus(ShiftStatus.OPEN);

            return allActiveShifts.stream()
                    .map(this::map)
                    .collect(Collectors.toList());
            }
        }

        ensureManagerBranchAccess(user, branchId);

        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }

        List<CashShift> shifts = cashShiftRepository.findAllByBranchIdAndStatus(branchId, ShiftStatus.OPEN);

        return shifts.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }

    public Page<ShiftResponse> getAllShifts(Long branchId, Long cashierId, LocalDateTime start, LocalDateTime end, ShiftStatus status, int page, int size) {
        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (user.getRole() == Role.MANAGER) {
            Long userBranchId = securityUtils.requireAssignedBranch(user);
            if (branchId == null) {
                branchId = userBranchId;
            } else if (!userBranchId.equals(branchId)) {
                throw new BadRequestException("Manager can only access their branch");
            }
        }

        Long branchFilter = branchId;
        Pageable pageable = PageRequest.of(page, size, Sort.by("openedAt").descending());

        Page<CashShift> shiftPage = cashShiftRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (branchFilter != null) predicates.add(cb.equal(root.get("branchId"), branchFilter));
            if (cashierId != null) predicates.add(cb.equal(root.get("cashierUserId"), cashierId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));

            if (start != null && end != null) {
                predicates.add(cb.between(root.get("openedAt"), start, end));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, pageable);
        return shiftPage.map(this::map);
    }

    public ShiftResponse getShift(Long shiftId) {
        User user = securityUtils.getCurrentUser();
        CashShift shift = getShiftForAdminOrManager(shiftId, user);
        return map(shift);
    }

    public Page<OrderResponse> getShiftOrders(Long shiftId, int page, int size) {
        User user = securityUtils.getCurrentUser();
        CashShift shift = getShiftForAdminOrManager(shiftId, user);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return orderRepository.findShiftOrders(
                        shift.getBranchId(),
                        shift.getCashierUserId(),
                        shift.getOpenedAt(),
                        shift.getClosedAt(),
                        pageable
                )
                .map(this::mapOrderSummary);
    }

    public Page<ExpenseResponse> getShiftExpenses(Long shiftId, int page, int size) {
        User user = securityUtils.getCurrentUser();
        CashShift shift = getShiftForAdminOrManager(shiftId, user);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return expenseRepository.findByShiftId(shift.getId(), pageable).map(this::mapExpenseSummary);
    }

    // Purchases paid out of THIS shift's cash drawer. These already reduce
    // Expected Cash (folded into totalExpenses by
    // PurchaseService.applyDrawerCashOutIfNeeded) — this endpoint exists so the
    // close-shift screen can show *which* purchases explain that number,
    // instead of leaving them invisible inside a single lump "Expenses" total.
    public Page<ShiftPurchaseSummaryResponse> getShiftPurchases(Long shiftId, int page, int size) {
        User user = securityUtils.getCurrentUser();
        CashShift shift = getShiftForAdminOrManager(shiftId, user);
        Pageable pageable = PageRequest.of(page, size);
        return purchaseRepository.findByCashShiftIdOrderByCreatedAtDesc(shift.getId(), pageable)
                .map(this::mapPurchaseSummary);
    }

    private CashShift getShiftForAdminOrManager(Long shiftId, User user) {
        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        ensureManagerBranchAccess(user, shift.getBranchId());
        return shift;
    }

    private ShiftPurchaseSummaryResponse mapPurchaseSummary(Purchase purchase) {
        String supplierName = purchase.getSupplier() != null ? purchase.getSupplier().getName() : "Unknown Supplier";
        return ShiftPurchaseSummaryResponse.builder()
                .purchaseId(purchase.getId())
                .invoiceNo(purchase.getInvoiceNo())
                .supplierName(supplierName)
                .cashSourceAmount(purchase.getCashSourceAmount())
                .status(purchase.getStatus())
                .createdAt(purchase.getCreatedAt())
                .build();
    }

    private ExpenseResponse mapExpenseSummary(Expense expense) {
        String cashierName = userRepository.findById(expense.getCashierUserId())
                .map(User::getUsername)
                .orElse("Unknown");
        String branchName = branchRepository.findById(expense.getBranchId())
                .map(Branch::getName)
                .orElse("Unknown");

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

//    @Transactional
//    public ShiftResponse closeShiftById(Long shiftId, CloseShiftRequest request) {
//
//        if (shiftId == null) {
//            throw new BadRequestException("Shift ID cannot be null");
//        }
//
//        User user = securityUtils.getCurrentUser();
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

        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (branchId == null || branchId == 0) {
            throw new BadRequestException("Please select a branch first");
        }

        ensureManagerBranchAccess(user, branchId);

        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, user.getId(), ShiftStatus.OPEN)
                .ifPresent(s -> {
                    throw new AlreadyExistsException("You already have an open shift in this branch");
                });

        CashShift shift = CashShift.builder()
                .branchId(branchId)
                .cashierUserId(user.getId())
                .status(ShiftStatus.OPEN)
                .openingCash(request.getOpeningCash())
                .openNote(request.getNote())
                .build();

        return map(persistNewShift(shift, "You already have an open shift in this branch"));
    }

//    @Transactional
//    public ShiftResponse addExpenseByShiftId(Long shiftId, CreateExpenseRequest request) {
//
//        if (shiftId == null) {
//            throw new BadRequestException("Shift ID cannot be null");
//        }
//
//        User user = securityUtils.getCurrentUser();
//        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
//            throw new RuntimeException("Not allowed");
//
//        CashShift shift = cashShiftRepository.findById(shiftId)
//                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
//
//        if (shift.getStatus() != ShiftStatus.OPEN)
//            throw new BadRequestException("Shift is closed");
//
//        Expense expense = Expense.builder()
//                .shiftId(shift.getId())
//                .branchId(shift.getBranchId())
//                .cashierUserId(user.getId())
//                .category(request.getCategory())
//                .amount(request.getAmount())
//                .description(request.getDescription().trim())
//                .build();
//
//        expenseRepository.save(expense);
//
//        shift.setTotalExpenses(shift.getTotalExpenses() + request.getAmount());
//        cashShiftRepository.save(shift);
//
//        return map(shift);
//    }

    @Transactional
    public ShiftResponse addCashDropByShiftId(Long shiftId, CreateCashDropRequest request) {
        if (shiftId == null) {
            throw new BadRequestException("Shift ID cannot be null");
        }
        User user = securityUtils.getCurrentUser();
        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER)
            throw new BadRequestException("Not allowed");

        CashShift shift = cashShiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        ensureManagerBranchAccess(user, shift.getBranchId());

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new BadRequestException("Shift is closed");

        CashDrop cashDrop = CashDrop.builder()
                .shiftId(shift.getId())
                .branchId(shift.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .reason(request.getReason().trim())
                .bankAccountId(resolveActiveBankAccountId(request.getBankAccountId()))
                .build();

        cashDropRepository.save(cashDrop);

        shift.setTotalCashDrops(shift.getTotalCashDrops() + request.getAmount());
        cashShiftRepository.save(shift);

        return map(shift);
    }

    private ShiftResponse map(CashShift s) {
        ShiftResponse response = ShiftResponse.builder()
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

        if (s.getBranchId() != null) {
            branchRepository.findById(s.getBranchId())
                    .ifPresent(branch -> {
                        response.setBranchName(branch.getName());
                        response.setBranchAddress(branch.getAddress());
                        response.setBranchPhone(branch.getPhone());
                        response.setBranchLogo(branch.getLogo());
                    });
        }

        if (s.getCashierUserId() != null) {
            userRepository.findById(s.getCashierUserId())
                    .ifPresent(user -> response.setCashierName(user.getUsername()));
        }
        return response;
    }

    private OrderResponse mapOrderSummary(Order order) {
        String customerName = "Walk-in Customer";
        if (order.getCustomerId() != null) {
            customerName = customerRepository.findById(order.getCustomerId())
                    .map(Customer::getName)
                    .orElse("Unknown Customer");
        }

        String cashierName = userRepository.findById(order.getCashierUserId())
                .map(User::getUsername)
                .orElse(null);

        return OrderResponse.builder()
                .id(order.getId())
                .invoiceNo(order.getInvoiceNo())
                .branchId(order.getBranchId())
                .branchName(order.getReceiptBranchName())
                .branchAddress(order.getReceiptBranchAddress())
                .branchPhone(order.getReceiptBranchPhone())
                .branchLogo(order.getReceiptBranchLogo())
                .cashierUserId(order.getCashierUserId())
                .cashierName(cashierName)
                .customerId(order.getCustomerId())
                .customerName(customerName)
                .orderType(order.getOrderType())
                .paymentMethod(order.getPaymentMethod())
                .saleMode(order.getSaleMode())
                .tableId(order.getTableId())
                .tableName(order.getTableName())
                .status(order.getStatus())
                .subTotal(order.getSubTotal())
                .billDiscount(order.getBillDiscount())
                .grandTotal(order.getGrandTotal())
                .paidAmount(order.getPaidAmount())
                .dueAmount(order.getDueAmount())
                .note(order.getNote())
                .createdAt(order.getCreatedAt())
                .build();
    }

}
