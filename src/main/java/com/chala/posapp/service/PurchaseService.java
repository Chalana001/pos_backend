package com.chala.posapp.service;

import com.chala.posapp.dto.CancelPurchaseRequest;
import com.chala.posapp.dto.CreatePurchaseRequest;
import com.chala.posapp.dto.PurchaseResponse;
import com.chala.posapp.dto.branch.BranchPurchaseRequest;
import com.chala.posapp.dto.grn.GrnItemRequest;
import com.chala.posapp.dto.grn.GrnItemResponse;
import com.chala.posapp.dto.grn.GrnResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CashShiftRepository;
import com.chala.posapp.repository.GrnItemRepository;
import com.chala.posapp.repository.GrnRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {
    private static final DateTimeFormatter PURCHASE_INVOICE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final int MONEY_SCALE = 2;
    private static final int UNIT_COST_SCALE = 6;

    private final PurchaseRepository purchaseRepository;
    private final GrnRepository grnRepository;
    private final GrnItemRepository grnItemRepository;
    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final GrnNumberService grnNumberService;
    private final UserRepository userRepository;
    private final CashShiftRepository cashShiftRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    private Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        return user.getBranchId();
    }

    private void ensureCreateAccess(User user, List<BranchPurchaseRequest> branches) {
        if (isAdminLike(user)) {
            return;
        }
        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        Long branchId = requireAssignedBranch(user);
        boolean invalidBranchFound = branches.stream()
                .map(BranchPurchaseRequest::getBranchId)
                .anyMatch(requestBranchId -> !Objects.equals(branchId, requestBranchId));
        if (invalidBranchFound) {
            throw new BadRequestException("Manager can only create purchases for their branch");
        }
    }

    private void ensurePurchaseAccess(User user, Purchase purchase) {
        if (isAdminLike(user)) {
            return;
        }
        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        Long branchId = requireAssignedBranch(user);
        boolean invalidBranchFound = purchase.getGrnList().stream()
                .map(grn -> grn.getBranch().getId())
                .anyMatch(grnBranchId -> !Objects.equals(branchId, grnBranchId));
        if (invalidBranchFound) {
            throw new BadRequestException("Manager can only access purchases for their branch");
        }
    }

    private boolean canAccessPurchase(User user, Purchase purchase) {
        if (isAdminLike(user)) {
            return true;
        }
        if (user.getRole() != Role.MANAGER || user.getBranchId() == null) {
            return false;
        }
        return purchase.getGrnList().stream()
                .allMatch(grn -> Objects.equals(user.getBranchId(), grn.getBranch().getId()));
    }

    private PurchaseStatus normalizeStatus(Purchase purchase) {
        return purchase.getStatus() == null ? PurchaseStatus.COMPLETED : purchase.getStatus();
    }

    private String resolveInvoiceNo(String rawInvoiceNo) {
        if (rawInvoiceNo == null) {
            return "PUR-" + LocalDateTime.now().format(PURCHASE_INVOICE_FORMAT);
        }

        String normalized = rawInvoiceNo.trim();
        if (normalized.isEmpty() || "PURCHASE".equalsIgnoreCase(normalized)) {
            return "PUR-" + LocalDateTime.now().format(PURCHASE_INVOICE_FORMAT);
        }
        return normalized;
    }

    @Transactional
    public PurchaseResponse createPurchase(CreatePurchaseRequest request) {
        User user = getLoggedUser();
        ensureCreateAccess(user, request.getBranches());
        BigDecimal requestedPaidAmount = normalizeMoney(request.getPaidAmount());
        if (requestedPaidAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Paid amount cannot be negative");
        }
        BigDecimal discountAmount = normalizeMoney(request.getDiscountAmount());
        if (discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Discount amount cannot be negative");
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));

        String invoiceNo = resolveInvoiceNo(request.getInvoiceNo());

        String paymentMethod = normalizePaymentMethod(request.getPaymentMethod());

        Purchase purchase = Purchase.builder()
                .supplier(supplier)
                .invoiceNo(invoiceNo)
                .paymentMethod(paymentMethod)
                .cashSource(resolveCashSource(request.getCashSource(), requestedPaidAmount, paymentMethod))
                .createdAt(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO)
                .status(PurchaseStatus.COMPLETED)
                .build();
        Purchase savedPurchase = purchaseRepository.save(purchase);

        List<PreparedPurchaseLine> preparedLines = preparePurchaseLines(request);
        BigDecimal grossTotal = preparedLines.stream()
                .map(PreparedPurchaseLine::grossLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (discountAmount.compareTo(grossTotal) > 0) {
            throw new BadRequestException("Discount amount cannot exceed purchase total");
        }
        applyDiscountAllocation(preparedLines, grossTotal, discountAmount);
        Map<GrnItemRequest, PreparedPurchaseLine> preparedLineByRequest = preparedLines.stream()
                .collect(Collectors.toMap(PreparedPurchaseLine::request, Function.identity()));

        List<GrnResponse> grnResponseList = new ArrayList<>();
        BigDecimal netGrandTotal = BigDecimal.ZERO;

        for (BranchPurchaseRequest branchReq : request.getBranches()) {
            Branch branch = branchRepository.findById(branchReq.getBranchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

            String grnNo = grnNumberService.generateGrnNo(branch.getId());

            GRN grn = GRN.builder()
                    .grnNo(grnNo)
                    .purchase(savedPurchase)
                    .supplier(supplier)
                    .branch(branch)
                    .receivedAt(LocalDateTime.now())
                    .totalAmount(BigDecimal.ZERO)
                    .note(invoiceNo)
                    .build();
            GRN savedGrn = grnRepository.save(grn);

            BigDecimal grnTotal = BigDecimal.ZERO;
            List<GrnItem> grnItems = new ArrayList<>();
            List<GrnItemResponse> itemResponses = new ArrayList<>();
            int index = 0;

            for (GrnItemRequest itemReq : branchReq.getItems()) {
                PreparedPurchaseLine preparedLine = preparedLineByRequest.get(itemReq);
                index++;
                Item item = preparedLine.item();
                BigDecimal effectiveCostPrice = preparedLine.effectiveCostPrice();
                BigDecimal netLineTotal = preparedLine.netLineTotal();

                item.setCostPrice(effectiveCostPrice);
                item.setSellingPrice(itemReq.getSellingPrice());
                itemRepository.save(item);

                int normalizedQty = preparedLine.normalizedQty();

                LocalDateTime expiry = itemReq.getExpiryDate() != null
                        ? itemReq.getExpiryDate().atStartOfDay()
                        : null;

                String batchCode = String.format("GRN-%s-%d-%d", grnNo, item.getId(), index);
                StockBatch batch = StockBatch.builder()
                        .branch(branch)
                        .item(item)
                        .supplier(supplier)
                        .quantity(normalizedQty)
                        .originalQuantity(normalizedQty)
                        .costPrice(effectiveCostPrice)
                        .sellingPrice(itemReq.getSellingPrice())
                        .sourceType(StockBatchSourceType.PURCHASE)
                        .batchCode(batchCode)
                        .receivedAt(LocalDateTime.now())
                        .expireDate(expiry)
                        .build();
                stockBatchRepository.save(batch);

                GrnItem grnItem = GrnItem.builder()
                        .grn(savedGrn)
                        .item(item)
                        .qty(normalizedQty)
                        .displayQty(itemReq.getQty().stripTrailingZeros())
                        .qtyUnit(QuantityConversionUtil.isMeasuredItem(item.getItemType())
                                ? (itemReq.getQtyUnit() == null ? QuantityConversionUtil.primaryDisplayUnit(item) : itemReq.getQtyUnit())
                                : QuantityConversionUtil.primaryDisplayUnit(item))
                        .costPrice(effectiveCostPrice)
                        .sellingPrice(itemReq.getSellingPrice())
                        .amount(netLineTotal)
                        .build();
                grnItems.add(grnItem);

                grnTotal = grnTotal.add(netLineTotal);

                itemResponses.add(GrnItemResponse.builder()
                        .itemId(item.getId())
                        .itemName(item.getName())
                        .barcode(item.getBarcode())
                        .qty(grnItem.getDisplayQty())
                        .qtyUnit(grnItem.getQtyUnit())
                        .costPrice(effectiveCostPrice)
                        .sellingPrice(itemReq.getSellingPrice())
                        .lineTotal(netLineTotal)
                        .build());
            }

            grnItemRepository.saveAll(grnItems);
            savedGrn.setTotalAmount(grnTotal);
            grnRepository.save(savedGrn);
            netGrandTotal = netGrandTotal.add(grnTotal);

            grnResponseList.add(GrnResponse.builder()
                    .id(savedGrn.getId())
                    .grnNo(savedGrn.getGrnNo())
                    .branchId(branch.getId())
                    .branchName(branch.getName())
                    .supplierName(supplier.getName())
                    .totalAmount(grnTotal)
                    .receivedAt(savedGrn.getReceivedAt())
                    .note(savedGrn.getNote())
                    .items(itemResponses)
                    .build());
        }

        if (requestedPaidAmount.compareTo(netGrandTotal) > 0) {
            throw new BadRequestException("Paid amount cannot exceed purchase total");
        }
        BigDecimal dueAmount = netGrandTotal.subtract(requestedPaidAmount);
        savedPurchase.setDiscountAmount(discountAmount);
        savedPurchase.setGrandTotal(netGrandTotal);
        savedPurchase.setPaidAmount(requestedPaidAmount);
        savedPurchase.setDueAmount(dueAmount);
        savedPurchase.setCashSourceAmount(requestedPaidAmount);
        applyDrawerCashOutIfNeeded(savedPurchase, requestedPaidAmount, user, request);
        purchaseRepository.save(savedPurchase);

        if (dueAmount.compareTo(BigDecimal.ZERO) > 0) {
            supplier.setDueAmount(normalizeMoney(supplier.getDueAmount()).add(dueAmount));
            supplierRepository.save(supplier);
        }

        return PurchaseResponse.builder()
                .purchaseId(savedPurchase.getId())
                .invoiceNo(savedPurchase.getInvoiceNo())
                .supplierId(supplier.getId())
                .supplierName(supplier.getName())
                .grandTotal(savedPurchase.getGrandTotal())
                .discountAmount(normalizeMoney(savedPurchase.getDiscountAmount()))
                .paidAmount(savedPurchase.getPaidAmount())
                .paymentMethod(savedPurchase.getPaymentMethod())
                .cashSource(savedPurchase.getCashSource())
                .cashShiftId(savedPurchase.getCashShiftId())
                .cashierUserId(savedPurchase.getCashierUserId())
                .cashSourceAmount(normalizeMoney(savedPurchase.getCashSourceAmount()))
                .cashSourceBranchId(savedPurchase.getCashSourceBranchId())
                .dueAmount(savedPurchase.getDueAmount())
                .status(normalizeStatus(savedPurchase))
                .cancelReason(savedPurchase.getCancelReason())
                .createdAt(savedPurchase.getCreatedAt())
                .canceledAt(savedPurchase.getCanceledAt())
                .grnList(grnResponseList)
                .build();
    }

    public Page<PurchaseResponse> getAllPurchases(int page, int size) {
        return getAllPurchases(null, null, null, null, null, page, size);
    }

    public Page<PurchaseResponse> getAllPurchases(
            String search,
            Long supplierId,
            PurchaseStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        User user = getLoggedUser();
        Pageable pageable = PageRequest.of(page, size);
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        Long managerBranchId = isAdminLike(user) ? null : requireAssignedBranch(user);

        Page<Purchase> purchasePage = purchaseRepository.findHistory(
                normalizedSearch,
                supplierId,
                status,
                fromDateTime,
                toDateTime,
                managerBranchId,
                pageable
        );

        return purchasePage.map(this::mapListResponse);
    }

    private PurchaseResponse mapListResponse(Purchase purchase) {
        return PurchaseResponse.builder()
                .purchaseId(purchase.getId())
                .invoiceNo(purchase.getInvoiceNo())
                .supplierId(purchase.getSupplier().getId())
                .supplierName(purchase.getSupplier().getName())
                .grandTotal(purchase.getGrandTotal())
                .discountAmount(normalizeMoney(purchase.getDiscountAmount()))
                .paidAmount(normalizeMoney(purchase.getPaidAmount()))
                .paymentMethod(purchase.getPaymentMethod())
                .cashSource(purchase.getCashSource())
                .cashShiftId(purchase.getCashShiftId())
                .cashierUserId(purchase.getCashierUserId())
                .cashSourceAmount(normalizeMoney(purchase.getCashSourceAmount()))
                .cashSourceBranchId(purchase.getCashSourceBranchId())
                .dueAmount(normalizeMoney(purchase.getDueAmount()))
                .status(normalizeStatus(purchase))
                .cancelReason(normalizeStatus(purchase) == PurchaseStatus.CANCELED ? purchase.getCancelReason() : null)
                .createdAt(purchase.getCreatedAt())
                .canceledAt(normalizeStatus(purchase) == PurchaseStatus.CANCELED ? purchase.getCanceledAt() : null)
                .grnList(null)
                .build();
    }

    public PurchaseResponse getPurchaseById(Long id) {
        User user = getLoggedUser();

        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
        ensurePurchaseAccess(user, purchase);

        List<GrnResponse> grnList = purchase.getGrnList().stream()
                .map(grn -> {
                    List<GrnItem> dbItems = grnItemRepository.findByGrnId(grn.getId());

                    List<GrnItemResponse> itemResponses = dbItems.stream()
                            .map(item -> GrnItemResponse.builder()
                                    .itemId(item.getItem().getId())
                                    .itemName(item.getItem().getName())
                                    .barcode(item.getItem().getBarcode())
                                    .qty(item.getDisplayQty())
                                    .qtyUnit(item.getQtyUnit())
                                    .costPrice(item.getCostPrice())
                                    .sellingPrice(item.getSellingPrice())
                                    .lineTotal(item.getAmount())
                                    .build())
                            .collect(Collectors.toList());

                    return GrnResponse.builder()
                            .id(grn.getId())
                            .grnNo(grn.getGrnNo())
                            .branchId(grn.getBranch().getId())
                            .branchName(grn.getBranch().getName())
                            .totalAmount(grn.getTotalAmount())
                            .items(itemResponses)
                            .build();
                })
                .collect(Collectors.toList());

        return PurchaseResponse.builder()
                .purchaseId(purchase.getId())
                .invoiceNo(purchase.getInvoiceNo())
                .supplierId(purchase.getSupplier().getId())
                .supplierName(purchase.getSupplier().getName())
                .grandTotal(purchase.getGrandTotal())
                .discountAmount(normalizeMoney(purchase.getDiscountAmount()))
                .paidAmount(normalizeMoney(purchase.getPaidAmount()))
                .paymentMethod(purchase.getPaymentMethod())
                .cashSource(purchase.getCashSource())
                .cashShiftId(purchase.getCashShiftId())
                .cashierUserId(purchase.getCashierUserId())
                .cashSourceAmount(normalizeMoney(purchase.getCashSourceAmount()))
                .cashSourceBranchId(purchase.getCashSourceBranchId())
                .dueAmount(normalizeMoney(purchase.getDueAmount()))
                .status(normalizeStatus(purchase))
                .cancelReason(normalizeStatus(purchase) == PurchaseStatus.CANCELED ? purchase.getCancelReason() : null)
                .createdAt(purchase.getCreatedAt())
                .canceledAt(normalizeStatus(purchase) == PurchaseStatus.CANCELED ? purchase.getCanceledAt() : null)
                .grnList(grnList)
                .build();
    }

    @Transactional
    public PurchaseResponse cancelPurchase(Long purchaseId, CancelPurchaseRequest request) {
        User user = getLoggedUser();

        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
        ensurePurchaseAccess(user, purchase);

        if (normalizeStatus(purchase) == PurchaseStatus.CANCELED) {
            throw new AlreadyExistsException("Purchase already canceled");
        }

        List<StockBatch> batchesToDelete = new ArrayList<>();
        for (GRN grn : purchase.getGrnList()) {
            List<StockBatch> batches = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                    grn.getBranch().getId(),
                    "GRN-" + grn.getGrnNo() + "-"
            );

            boolean stockChanged = batches.stream().anyMatch(batch ->
                    batch.getQuantity() == null
                            || batch.getOriginalQuantity() == null
                            || !Objects.equals(batch.getQuantity(), batch.getOriginalQuantity()));
            if (stockChanged) {
                throw new BadRequestException("Cannot cancel purchase because stock from this purchase has already been sold or adjusted");
            }

            batchesToDelete.addAll(batches);
        }

        stockBatchRepository.deleteAll(batchesToDelete);
        reverseDrawerCashOutIfOpen(purchase);
        BigDecimal purchaseDue = normalizeMoney(purchase.getDueAmount());
        if (purchaseDue.compareTo(BigDecimal.ZERO) > 0) {
            Supplier supplier = purchase.getSupplier();
            BigDecimal nextSupplierDue = normalizeMoney(supplier.getDueAmount()).subtract(purchaseDue);
            supplier.setDueAmount(nextSupplierDue.max(BigDecimal.ZERO));
            supplierRepository.save(supplier);
            purchase.setDueAmount(BigDecimal.ZERO);
        }
        purchase.setStatus(PurchaseStatus.CANCELED);
        purchase.setCancelReason(request.getReason().trim());
        purchase.setCanceledAt(LocalDateTime.now());
        purchaseRepository.save(purchase);

        return getPurchaseById(purchase.getId());
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CashSource resolveCashSource(CashSource requestedCashSource, BigDecimal paidAmount, String paymentMethod) {
        if (paidAmount == null || paidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return CashSource.NONE;
        }
        if (!"CASH".equals(paymentMethod)) {
            return "BANK".equals(paymentMethod) ? CashSource.BANK : CashSource.NONE;
        }
        return requestedCashSource == null || requestedCashSource == CashSource.NONE
                ? CashSource.BRANCH_CASH
                : requestedCashSource;
    }

    private void applyDrawerCashOutIfNeeded(Purchase purchase, BigDecimal paidAmount, User user, CreatePurchaseRequest request) {
        if (purchase.getCashSource() != CashSource.CASH_DRAWER || paidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Long branchId = resolveDrawerBranchId(user, request);
        CashShift shift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, user.getId(), ShiftStatus.OPEN)
                .orElseThrow(() -> new BadRequestException("An open shift is required when purchase payment comes from the cash drawer"));

        double amount = paidAmount.doubleValue();
        shift.setTotalExpenses(shift.getTotalExpenses() + amount);
        cashShiftRepository.save(shift);
        purchase.setCashShiftId(shift.getId());
        purchase.setCashierUserId(user.getId());
        purchase.setCashSourceBranchId(branchId);
    }

    private Long resolveDrawerBranchId(User user, CreatePurchaseRequest request) {
        if (!isAdminLike(user)) {
            return requireAssignedBranch(user);
        }

        List<Long> purchaseBranchIds = request.getBranches().stream()
                .map(BranchPurchaseRequest::getBranchId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (purchaseBranchIds.isEmpty()) {
            throw new BadRequestException("Purchase branch is required for cash drawer source");
        }

        Long requestedBranchId = request.getCashSourceBranchId();
        if (requestedBranchId == null || requestedBranchId == 0) {
            if (purchaseBranchIds.size() == 1) {
                return purchaseBranchIds.get(0);
            }
            throw new BadRequestException("Drawer branch is required when a cash drawer purchase is split across multiple branches");
        }

        if (!purchaseBranchIds.contains(requestedBranchId)) {
            throw new BadRequestException("Drawer branch must be one of the purchase branches");
        }
        return requestedBranchId;
    }

    private void reverseDrawerCashOutIfOpen(Purchase purchase) {
        if (purchase.getCashSource() != CashSource.CASH_DRAWER || purchase.getCashShiftId() == null) {
            return;
        }

        CashShift shift = cashShiftRepository.findById(purchase.getCashShiftId())
                .orElseThrow(() -> new ResourceNotFoundException("Linked cash shift not found"));
        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new BadRequestException("Cannot cancel purchase because its drawer payment belongs to a closed shift");
        }

        double amount = normalizeMoney(purchase.getCashSourceAmount()).doubleValue();
        shift.setTotalExpenses(Math.max(0, shift.getTotalExpenses() - amount));
        cashShiftRepository.save(shift);
    }

    private List<PreparedPurchaseLine> preparePurchaseLines(CreatePurchaseRequest request) {
        List<PreparedPurchaseLine> preparedLines = new ArrayList<>();

        for (BranchPurchaseRequest branchReq : request.getBranches()) {
            if (branchReq.getItems() == null || branchReq.getItems().isEmpty()) {
                continue;
            }

            for (GrnItemRequest itemReq : branchReq.getItems()) {
                Item item = itemRepository.findById(itemReq.getItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

                if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
                    throw new BadRequestException("Only stock-tracked grocery items can be purchased or added to GRN. Item: " + item.getName());
                }

                int normalizedQty = QuantityConversionUtil.normalizeQuantity(
                        item.getItemType(),
                        item.getDefaultUnit(),
                        itemReq.getQty(),
                        QuantityConversionUtil.isMeasuredItem(item.getItemType())
                                ? (itemReq.getQtyUnit() == null ? QuantityConversionUtil.primaryDisplayUnit(item) : itemReq.getQtyUnit())
                                : QuantityConversionUtil.primaryDisplayUnit(item)
                );
                BigDecimal grossLineTotal = QuantityConversionUtil.calculateActualAmount(item, itemReq.getCostPrice(), normalizedQty);
                preparedLines.add(new PreparedPurchaseLine(itemReq, item, normalizedQty, grossLineTotal));
            }
        }

        return preparedLines;
    }

    private void applyDiscountAllocation(List<PreparedPurchaseLine> preparedLines, BigDecimal grossTotal, BigDecimal discountAmount) {
        if (preparedLines.isEmpty()) {
            return;
        }

        if (discountAmount.compareTo(BigDecimal.ZERO) == 0 || grossTotal.compareTo(BigDecimal.ZERO) == 0) {
            preparedLines.forEach(line -> line.applyDiscount(BigDecimal.ZERO));
            return;
        }

        BigDecimal allocatedDiscount = BigDecimal.ZERO;
        for (int i = 0; i < preparedLines.size(); i++) {
            PreparedPurchaseLine line = preparedLines.get(i);
            BigDecimal lineDiscount = i == preparedLines.size() - 1
                    ? discountAmount.subtract(allocatedDiscount)
                    : discountAmount
                    .multiply(line.grossLineTotal())
                    .divide(grossTotal, MONEY_SCALE, RoundingMode.HALF_UP);

            if (lineDiscount.compareTo(line.grossLineTotal()) > 0) {
                lineDiscount = line.grossLineTotal();
            }
            allocatedDiscount = allocatedDiscount.add(lineDiscount);
            line.applyDiscount(lineDiscount);
        }
    }

    private static BigDecimal effectiveUnitCost(Item item, BigDecimal netLineTotal, int normalizedQty) {
        if (normalizedQty <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseUnitsPerPrimaryUnit = BigDecimal.valueOf(1000);
        return netLineTotal
                .multiply(baseUnitsPerPrimaryUnit)
                .divide(BigDecimal.valueOf(normalizedQty), UNIT_COST_SCALE, RoundingMode.HALF_UP)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static final class PreparedPurchaseLine {
        private final GrnItemRequest request;
        private final Item item;
        private final int normalizedQty;
        private final BigDecimal grossLineTotal;
        private BigDecimal netLineTotal;
        private BigDecimal effectiveCostPrice;

        private PreparedPurchaseLine(GrnItemRequest request, Item item, int normalizedQty, BigDecimal grossLineTotal) {
            this.request = request;
            this.item = item;
            this.normalizedQty = normalizedQty;
            this.grossLineTotal = grossLineTotal;
            applyDiscount(BigDecimal.ZERO);
        }

        private GrnItemRequest request() {
            return request;
        }

        private Item item() {
            return item;
        }

        private int normalizedQty() {
            return normalizedQty;
        }

        private BigDecimal grossLineTotal() {
            return grossLineTotal;
        }

        private BigDecimal netLineTotal() {
            return netLineTotal;
        }

        private BigDecimal effectiveCostPrice() {
            return effectiveCostPrice;
        }

        private void applyDiscount(BigDecimal discount) {
            this.netLineTotal = grossLineTotal.subtract(discount).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            this.effectiveCostPrice = effectiveUnitCost(item, netLineTotal, normalizedQty);
        }
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }
}
