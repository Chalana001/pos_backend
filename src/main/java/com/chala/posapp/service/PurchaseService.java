package com.chala.posapp.service;

import com.chala.posapp.audit.Audited;
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
import com.chala.posapp.entity.stock.StockAdjustment;
import com.chala.posapp.entity.stock.StockAdjustmentType;
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
import com.chala.posapp.repository.PurchaseReturnRepository;
import com.chala.posapp.repository.StockAdjustmentRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SupplierPaymentRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private final PurchaseRepository       purchaseRepository;
    private final GrnRepository            grnRepository;
    private final GrnItemRepository        grnItemRepository;
    private final ItemRepository           itemRepository;
    private final StockBatchRepository     stockBatchRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final GrnNumberService grnNumberService;
    private final SecurityUtils securityUtils;
    private final CashShiftRepository cashShiftRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final UserRepository userRepository;
    private final ReportCacheInvalidator reportCacheInvalidator;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureCreateAccess(User user, List<BranchPurchaseRequest> branches) {
        if (securityUtils.isAdminLike(user)) {
            return;
        }
        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        Long branchId = securityUtils.requireAssignedBranch(user);
        boolean invalidBranchFound = branches.stream()
                .map(BranchPurchaseRequest::getBranchId)
                .anyMatch(requestBranchId -> !Objects.equals(branchId, requestBranchId));
        if (invalidBranchFound) {
            throw new BadRequestException("Manager can only create purchases for their branch");
        }
    }

    private void ensurePurchaseAccess(User user, Purchase purchase) {
        if (securityUtils.isAdminLike(user)) {
            return;
        }
        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        Long branchId = securityUtils.requireAssignedBranch(user);
        boolean invalidBranchFound = purchase.getGrnList().stream()
                .map(grn -> grn.getBranch().getId())
                .anyMatch(grnBranchId -> !Objects.equals(branchId, grnBranchId));
        if (invalidBranchFound) {
            throw new BadRequestException("Manager can only access purchases for their branch");
        }
    }

    private boolean canAccessPurchase(User user, Purchase purchase) {
        if (securityUtils.isAdminLike(user)) {
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

    /**
     * Refuse a supplier invoice number that a live bill is already using.
     *
     * There was no check here at all until now: the database's unique constraint was the
     * only thing stopping it, so entering the same invoice twice produced a raw integrity
     * violation rather than a sentence anyone could act on.
     *
     * Only COMPLETED bills count. A cancelled bill keeps the supplier's real invoice number
     * — it is what is printed on the paper in the shop's hand — so the number must be free
     * for its replacement to use. V47 enforces exactly this rule in MySQL through a
     * generated column; this is the readable half of it, and the only half the test profile
     * has, since Hibernate builds that schema from the entity and never runs the migration.
     *
     * @param excludeId the bill being superseded, which is still COMPLETED while its
     *                  replacement is validated; null when creating from scratch
     */
    private void ensureInvoiceNoIsFree(Long supplierId, String invoiceNo, Long excludeId) {
        boolean taken = excludeId == null
                ? purchaseRepository.existsBySupplierIdAndInvoiceNoAndStatus(
                        supplierId, invoiceNo, PurchaseStatus.COMPLETED)
                : purchaseRepository.existsBySupplierIdAndInvoiceNoAndStatusAndIdNot(
                        supplierId, invoiceNo, PurchaseStatus.COMPLETED, excludeId);
        if (taken) {
            throw new AlreadyExistsException(
                    "Invoice " + invoiceNo + " is already recorded for this supplier");
        }
    }

    @Transactional
    public PurchaseResponse createPurchase(CreatePurchaseRequest request) {
        return createPurchase(request, null);
    }

    /**
     * @param supersedesId when this bill is replacing another, the one being replaced. It is
     *                     still COMPLETED while this runs, so the invoice-number check has
     *                     to be told to look past it — a replacement keeps the supplier's
     *                     original invoice number, which is the entire point of a supersede.
     */
    @Transactional
    public PurchaseResponse createPurchase(CreatePurchaseRequest request, Long supersedesId) {
        User user = securityUtils.getCurrentUser();
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
        ensureInvoiceNoIsFree(supplier.getId(), invoiceNo, supersedesId);

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

                // A fully-free line pays nothing, so its effective cost is 0. That 0 is the
                // true cost of the batch, but it must not overwrite the item's reference
                // cost — margin reports would show a fake 100% profit afterwards.
                if (preparedLine.normalizedQty() > 0) {
                    item.setCostPrice(effectiveCostPrice);
                }
                item.setSellingPrice(itemReq.getSellingPrice());
                itemRepository.save(item);

                int normalizedQty = preparedLine.normalizedQty();
                int normalizedFreeQty = preparedLine.normalizedFreeQty();
                int totalReceivedQty = preparedLine.totalReceivedQty();

                if (Boolean.TRUE.equals(itemReq.getZeroNegativeStock())) {
                    zeroOutNegativeStock(item, branch, user);
                }

                LocalDateTime expiry = itemReq.getExpiryDate() != null
                        ? itemReq.getExpiryDate().atStartOfDay()
                        : null;

                String batchCode = String.format("GRN-%s-%d-%d", grnNo, item.getId(), index);
                StockBatch batch = StockBatch.builder()
                        .branch(branch)
                        .item(item)
                        .supplier(supplier)
                        .quantity(totalReceivedQty)
                        .originalQuantity(totalReceivedQty)
                        .costPrice(effectiveCostPrice)
                        .sellingPrice(itemReq.getSellingPrice())
                        .sourceType(StockBatchSourceType.PURCHASE)
                        .batchCode(batchCode)
                        .receivedAt(LocalDateTime.now())
                        .expireDate(expiry)
                        .build();
                stockBatchRepository.save(batch);

                BigDecimal displayQty = itemReq.getQty() == null
                        ? BigDecimal.ZERO
                        : itemReq.getQty().stripTrailingZeros();
                BigDecimal displayFreeQty = itemReq.getFreeQty() == null
                        ? BigDecimal.ZERO
                        : itemReq.getFreeQty().stripTrailingZeros();

                GrnItem grnItem = GrnItem.builder()
                        .grn(savedGrn)
                        .item(item)
                        .qty(normalizedQty)
                        .displayQty(displayQty)
                        .freeQty(normalizedFreeQty)
                        .displayFreeQty(displayFreeQty)
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
                        .id(grnItem.getId())
                        .itemId(item.getId())
                        .itemName(item.getName())
                        .altName(item.getAltName())
                        .barcode(item.getBarcode())
                        .qty(grnItem.getDisplayQty())
                        .freeQty(grnItem.getDisplayFreeQty())
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

        // Receiving goods moves stock and changes what is owed to the supplier. Neither
        // report-top-suppliers nor report-inventory-val had any eviction before this.
        reportCacheInvalidator.procurementChanged();

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
        User user = securityUtils.getCurrentUser();
        Pageable pageable = PageRequest.of(page, size);
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        Long managerBranchId = securityUtils.isAdminLike(user) ? null : securityUtils.requireAssignedBranch(user);

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
                .replacesPurchaseId(purchase.getReplacesPurchaseId())
                .replacedByPurchaseId(purchase.getReplacedByPurchaseId())
                .build();
    }

    public PurchaseResponse getPurchaseById(Long id) {
        User user = securityUtils.getCurrentUser();

        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
        ensurePurchaseAccess(user, purchase);

        // Undo the discount allocation so a rebuild can pre-fill what was actually typed.
        //
        // Every line was discounted by the same fraction of the bill: applyDiscountAllocation
        // gives line i a share proportional to its gross, so net = gross x (1 - D/G) for all
        // of them. Summing that, N = G - D, which makes the way back G/N = (N + D)/N, one
        // factor for the whole purchase. Computed across every GRN because the discount was
        // allocated across every GRN, not per branch.
        BigDecimal grossUpFactor = grossUpFactorFor(purchase);

        List<GrnResponse> grnList = purchase.getGrnList().stream()
                .map(grn -> {
                    List<GrnItem> dbItems = grnItemRepository.findByGrnId(grn.getId());
                    Map<Long, LocalDate> expiryByItemId = expiryByItemIdFor(grn);

                    List<GrnItemResponse> itemResponses = dbItems.stream()
                            .map(item -> GrnItemResponse.builder()
                                    .id(item.getId())
                                    .itemId(item.getItem().getId())
                                    .itemName(item.getItem().getName())
                                    .altName(item.getItem().getAltName())
                                    .barcode(item.getItem().getBarcode())
                                    .qty(item.getDisplayQty())
                                    .freeQty(item.getDisplayFreeQty())
                                    .qtyUnit(item.getQtyUnit())
                                    .costPrice(item.getCostPrice())
                                    .sellingPrice(item.getSellingPrice())
                                    .lineTotal(item.getAmount())
                                    .expiryDate(expiryByItemId.get(item.getItem().getId()))
                                    .grossCostPrice(normalizeMoney(item.getCostPrice()).multiply(grossUpFactor)
                                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP))
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

        String replaceBlockedReason = describeCancelBlock(purchase);

        // Return summary
        long returnCount = purchaseReturnRepository.countByPurchaseId(purchase.getId());
        BigDecimal totalReturnedAmount = purchaseReturnRepository
                .findByPurchaseIdOrderByCreatedAtDesc(purchase.getId())
                .stream()
                .map(r -> r.getTotalReturnAmount() == null ? BigDecimal.ZERO : r.getTotalReturnAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

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
                .canceledByUsername(normalizeStatus(purchase) == PurchaseStatus.CANCELED
                        ? lookupUsername(purchase.getCanceledByUserId()) : null)
                .grnList(grnList)
                .hasReturns(returnCount > 0)
                .returnCount((int) returnCount)
                .totalReturnedAmount(totalReturnedAmount)
                .replacesPurchaseId(purchase.getReplacesPurchaseId())
                .replacesInvoiceNo(lookupInvoiceNo(purchase.getReplacesPurchaseId()))
                .replacedByPurchaseId(purchase.getReplacedByPurchaseId())
                .replacedByInvoiceNo(lookupInvoiceNo(purchase.getReplacedByPurchaseId()))
                // Only answered on the detail fetch: it costs a batch scan per GRN, which
                // is wasted work on a list page where no button is being drawn from it.
                .canReplace(replaceBlockedReason == null)
                .replaceBlockedReason(replaceBlockedReason)
                .build();
    }

    /**
     * Expiry dates for a GRN's lines, keyed by item.
     *
     * Batches are coded {@code GRN-<grnNo>-<itemId>-<index>}, so a line's batch can be found
     * by item — but only unambiguously when that item appears once in the GRN. Where the
     * same item is received on several lines of one GRN there is no way to tell from the
     * line alone which batch is which, so those are left out entirely rather than guessed:
     * a rebuild pre-filled with the wrong expiry is worse than one pre-filled with none.
     */
    private Map<Long, LocalDate> expiryByItemIdFor(GRN grn) {
        List<StockBatch> batches = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                grn.getBranch().getId(), "GRN-" + grn.getGrnNo() + "-");

        Map<Long, List<StockBatch>> byItem = batches.stream()
                .filter(batch -> batch.getItem() != null)
                .collect(Collectors.groupingBy(batch -> batch.getItem().getId()));

        Map<Long, LocalDate> expiry = new java.util.HashMap<>();
        byItem.forEach((itemId, itemBatches) -> {
            if (itemBatches.size() != 1) return;
            LocalDateTime expireDate = itemBatches.get(0).getExpireDate();
            if (expireDate != null) expiry.put(itemId, expireDate.toLocalDate());
        });
        return expiry;
    }

    /**
     * (net + discount) / net for this purchase, or ONE when there was no discount.
     *
     * Multiplying a stored effective cost by this gives back the gross the operator typed.
     * The last line of a discounted bill absorbed a rounding remainder rather than an exact
     * proportional share, so that one line can come back a cent out; the backend re-allocates
     * on save, and a cent on one line is not what this is protecting against.
     */
    private BigDecimal grossUpFactorFor(Purchase purchase) {
        BigDecimal discount = normalizeMoney(purchase.getDiscountAmount());
        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal net = purchase.getGrnList().stream()
                .map(grn -> normalizeMoney(grn.getTotalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return net.add(discount).divide(net, UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }

    /** Who a stored user id belongs to, or null when the id is absent or the user is gone. */
    private String lookupUsername(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }

    /** The invoice number of a linked bill, for showing a chain without a second fetch. */
    private String lookupInvoiceNo(Long purchaseId) {
        if (purchaseId == null) return null;
        return purchaseRepository.findById(purchaseId).map(Purchase::getInvoiceNo).orElse(null);
    }

    /**
     * Why this bill cannot be voided right now, as a phrase, or null if it can be.
     *
     * Read-only and side-effect free, so it can answer three different questions with one
     * definition of the rules: the guard inside {@link #cancelPurchase}, the pre-check in
     * {@link #replacePurchase} — which has to fail before anything is created — and the
     * {@code canReplace} flag the details screen uses to decide whether to offer the button
     * at all. Two copies of these checks would eventually disagree, and the one that
     * disagreed quietly would be the UI.
     */
    private String describeCancelBlock(Purchase purchase) {
        if (normalizeStatus(purchase) == PurchaseStatus.CANCELED) {
            return "it is already canceled";
        }

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
                return "stock from this purchase has already been sold or adjusted";
            }
        }

        if (purchase.getCashSource() == CashSource.CASH_DRAWER && purchase.getCashShiftId() != null) {
            CashShift shift = cashShiftRepository.findById(purchase.getCashShiftId()).orElse(null);
            if (shift == null) {
                return "its linked cash shift no longer exists";
            }
            if (shift.getStatus() != ShiftStatus.OPEN) {
                return "its drawer payment belongs to a closed shift";
            }
        }

        // Voiding only gives back what is still OWED. SupplierService allocates a payment by
        // moving the amount from due to paid, and nothing here reverses those rows, so a
        // bill that has been part-paid would leave the supplier ledger overstated by exactly
        // what was already settled — and leave the payment hanging off a cancelled bill.
        // Re-pointing payments at a replacement is a bigger question than this feature;
        // until then the honest answer is to refuse.
        if (supplierPaymentRepository.existsByPurchaseId(purchase.getId())) {
            return "supplier payments have been recorded against it";
        }

        return null;
    }

    /**
     * Do the voiding, assuming the guards above have already passed.
     *
     * Shared by the plain cancel and by a supersede, so the two can never drift into
     * reversing different things.
     */
    private void voidPurchase(Purchase purchase, String reason, User actor) {
        List<StockBatch> batchesToDelete = new ArrayList<>();
        for (GRN grn : purchase.getGrnList()) {
            batchesToDelete.addAll(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                    grn.getBranch().getId(),
                    "GRN-" + grn.getGrnNo() + "-"
            ));
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
        purchase.setCancelReason(reason);
        purchase.setCanceledAt(LocalDateTime.now());
        purchase.setCanceledByUserId(actor == null ? null : actor.getId());
        purchaseRepository.save(purchase);

        // A cancelled purchase must stop counting towards purchase and stock reporting on
        // the next read rather than whenever the cache happened to expire.
        reportCacheInvalidator.procurementChanged();
        reportCacheInvalidator.stockChanged();
    }

    @Audited(entity = "PURCHASE", action = "CANCEL", idExpression = "#purchaseId")
    @Transactional
    public PurchaseResponse cancelPurchase(Long purchaseId, CancelPurchaseRequest request) {
        User user = securityUtils.getCurrentUser();

        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
        ensurePurchaseAccess(user, purchase);

        if (normalizeStatus(purchase) == PurchaseStatus.CANCELED) {
            throw new AlreadyExistsException("Purchase already canceled");
        }

        String blocked = describeCancelBlock(purchase);
        if (blocked != null) {
            throw new BadRequestException("Cannot cancel purchase because " + blocked);
        }

        voidPurchase(purchase, request.getReason().trim(), user);

        return getPurchaseById(purchase.getId());
    }

    /**
     * Correct a wrong bill: issue a replacement and void the original, atomically.
     *
     * Editing a posted purchase in place is not something an auditable system should offer,
     * so this does what accounting has always done instead — void and reissue, with the two
     * bills linked in both directions. The original is never mutated beyond being marked
     * cancelled and pointed at its successor.
     *
     * <p><strong>Order is load-bearing.</strong> The guards run first, so the operator is
     * told the bill cannot be voided *before* re-typing forty lines rather than after. Then
     * the replacement is created and fully validated, and only then is the original voided.
     * A failure anywhere in the new bill therefore aborts with nothing destroyed — whereas
     * cancelling first would delete the original's stock and leave the shop with neither
     * bill if the replacement turned out to be invalid.
     *
     * <p>The two bills' batches carry different {@code GRN-<no>-} codes, so both existing
     * briefly inside one transaction cannot collide.
     *
     * <p>One transaction, one endpoint, deliberately. Cancel-then-create as two calls from
     * the browser leaves the shop with stock deleted and no replacement whenever the second
     * call is the one that fails. Under contention the ordering still holds: a second
     * manager replacing the same bill passes the pre-check, creates their replacement, then
     * fails at the void step with "already canceled" — and the whole transaction, their new
     * bill included, rolls back.
     */
    @Audited(entity = "PURCHASE", action = "REPLACE", idExpression = "#oldPurchaseId")
    @Transactional
    public PurchaseResponse replacePurchase(Long oldPurchaseId, CreatePurchaseRequest request) {
        User user = securityUtils.getCurrentUser();

        Purchase original = purchaseRepository.findById(oldPurchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
        ensurePurchaseAccess(user, original);

        if (normalizeStatus(original) == PurchaseStatus.CANCELED) {
            throw new AlreadyExistsException("Purchase already canceled");
        }

        String blocked = describeCancelBlock(original);
        if (blocked != null) {
            throw new BadRequestException("Cannot rebuild this purchase because " + blocked);
        }

        // Void the original BEFORE creating the replacement, and do not reorder this.
        //
        // A replacement keeps the supplier's own invoice number, and V47's unique index
        // covers COMPLETED rows only. MySQL checks that index per statement, not at commit,
        // so the instant both rows are COMPLETED — which is what creating first means — the
        // insert is rejected with a duplicate key, inside the transaction, every time.
        //
        // Nothing is risked by voiding first. The guarantee that a bad replacement destroys
        // nothing comes from this method being one transaction, not from the ordering: if
        // the create below throws, the void rolls back with it and the original is intact.
        // The two bills' stock batches carry different GRN- codes, so they cannot collide
        // while both briefly exist here either.
        voidPurchase(original, "Superseded", user);

        // Creating validates everything: branch access for the new lines, the amounts, and
        // the invoice number. Passing the original's id keeps the service-level uniqueness
        // check off its back — it is CANCELED by now, but the check is also what protects
        // the test profile, where Hibernate builds the schema and V47's index does not exist.
        PurchaseResponse createdResponse = createPurchase(request, oldPurchaseId);

        Purchase replacement = purchaseRepository.findById(createdResponse.getPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Replacement purchase not found"));

        // Now that the replacement has a number, say what superseded what. A cancelled bill
        // with no successor named is indistinguishable from an ordinary cancellation, which
        // is most of the audit value this feature exists to buy.
        original.setCancelReason("Superseded by " + replacement.getInvoiceNo());
        original.setReplacedByPurchaseId(replacement.getId());
        replacement.setReplacesPurchaseId(original.getId());
        purchaseRepository.save(original);
        purchaseRepository.save(replacement);

        return getPurchaseById(replacement.getId());
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
        if (!securityUtils.isAdminLike(user)) {
            return securityUtils.requireAssignedBranch(user);
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

    private void zeroOutNegativeStock(Item item, Branch branch, User user) {
        List<StockBatch> negativeBatches = stockBatchRepository
                .findByBranchIdAndItemIdAndQuantityLessThan(branch.getId(), item.getId(), 0);

        for (StockBatch negativeBatch : negativeBatches) {
            int qtyChange = -negativeBatch.getQuantity();
            negativeBatch.setQuantity(0);
            stockBatchRepository.save(negativeBatch);

            StockAdjustment adjustment = StockAdjustment.builder()
                    .branchId(branch.getId())
                    .itemId(item.getId())
                    .type(StockAdjustmentType.MANUAL)
                    .qtyChange(qtyChange)
                    .displayQtyChange(QuantityConversionUtil.toDisplayQuantity(item, qtyChange))
                    .qtyUnit(QuantityConversionUtil.isMeasuredItem(item.getItemType())
                            ? QuantityConversionUtil.primaryDisplayUnit(item)
                            : item.getDefaultUnit())
                    .reason("Negative stock corrected before purchase")
                    .userId(user.getId())
                    .createdAt(LocalDateTime.now())
                    .build();
            stockAdjustmentRepository.save(adjustment);
        }
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

                MeasurementUnit resolvedUnit = QuantityConversionUtil.isMeasuredItem(item.getItemType())
                        ? (itemReq.getQtyUnit() == null ? QuantityConversionUtil.primaryDisplayUnit(item) : itemReq.getQtyUnit())
                        : QuantityConversionUtil.primaryDisplayUnit(item);

                BigDecimal freeQty = itemReq.getFreeQty();
                if (freeQty != null && freeQty.signum() < 0) {
                    throw new BadRequestException("Free quantity cannot be negative. Item: " + item.getName());
                }
                boolean hasPaidQty = itemReq.getQty() != null && itemReq.getQty().signum() > 0;
                boolean hasFreeQty = freeQty != null && freeQty.signum() > 0;
                if (!hasPaidQty && !hasFreeQty) {
                    throw new BadRequestException("Quantity must be greater than zero. Item: " + item.getName());
                }

                // A line may be entirely free of charge (supplier FOC-only delivery):
                // paid qty 0 is allowed as long as free qty is present.
                int normalizedQty = hasPaidQty
                        ? QuantityConversionUtil.normalizeQuantity(item.getItemType(), item.getDefaultUnit(), itemReq.getQty(), resolvedUnit)
                        : 0;
                int normalizedFreeQty = hasFreeQty
                        ? QuantityConversionUtil.normalizeQuantity(item.getItemType(), item.getDefaultUnit(), freeQty, resolvedUnit)
                        : 0;
                BigDecimal grossLineTotal = QuantityConversionUtil.calculateActualAmount(item, itemReq.getCostPrice(), normalizedQty);
                preparedLines.add(new PreparedPurchaseLine(itemReq, item, normalizedQty, normalizedFreeQty, grossLineTotal));
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

    private static BigDecimal effectiveUnitCost(BigDecimal netLineTotal, int totalReceivedQty) {
        if (totalReceivedQty <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseUnitsPerPrimaryUnit = BigDecimal.valueOf(1000);
        return netLineTotal
                .multiply(baseUnitsPerPrimaryUnit)
                .divide(BigDecimal.valueOf(totalReceivedQty), UNIT_COST_SCALE, RoundingMode.HALF_UP)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static final class PreparedPurchaseLine {
        private final GrnItemRequest request;
        private final Item item;
        private final int normalizedQty;
        private final int normalizedFreeQty;
        private final BigDecimal grossLineTotal;
        private BigDecimal netLineTotal;
        private BigDecimal effectiveCostPrice;

        private PreparedPurchaseLine(GrnItemRequest request, Item item, int normalizedQty, int normalizedFreeQty, BigDecimal grossLineTotal) {
            this.request = request;
            this.item = item;
            this.normalizedQty = normalizedQty;
            this.normalizedFreeQty = normalizedFreeQty;
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

        private int normalizedFreeQty() {
            return normalizedFreeQty;
        }

        private int totalReceivedQty() {
            return normalizedQty + normalizedFreeQty;
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
            // Free units dilute the unit cost: the money paid for the line is spread
            // over everything received, so "10 + 2 free" costs 10/12 per unit.
            this.effectiveCostPrice = effectiveUnitCost(netLineTotal, totalReceivedQty());
        }
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }
}
