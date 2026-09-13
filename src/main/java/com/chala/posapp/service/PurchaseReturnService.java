package com.chala.posapp.service;

import com.chala.posapp.dto.purchaseReturns.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseReturnService {

    private final PurchaseRepository            purchaseRepository;
    private final GrnRepository                 grnRepository;
    private final GrnItemRepository             grnItemRepository;
    private final PurchaseReturnRepository      purchaseReturnRepository;
    private final PurchaseReturnItemRepository  purchaseReturnItemRepository;
    private final SecurityUtils                 securityUtils;
    private final SupplierRepository            supplierRepository;
    private final StockBatchRepository          stockBatchRepository;
    private final ItemRepository                itemRepository;
    private final UserRepository                userRepository;
    private final ReportCacheInvalidator        reportCacheInvalidator;

    // ---------------------------------------------------------------
    // Helpers, BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike(), use SecurityUtils instead
    // ---------------------------------------------------------------

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureBranchAccess(User user, Long branchId) {
        if (securityUtils.isAdminLike(user)) return;
        if (!securityUtils.requireAssignedBranch(user).equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    private BigDecimal roundMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------
    // PUBLIC: Process a partial purchase return (Debit Note)
    // ---------------------------------------------------------------

    @Transactional
    public PurchaseReturnResponse processReturn(Long purchaseId, CreatePurchaseReturnRequest request) {

        // 1. Load & validate purchase
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));

        if (purchase.getStatus() == PurchaseStatus.CANCELED) {
            throw new BadRequestException("Cannot process a return for a canceled purchase");
        }

        // 2. Load & validate GRN
        GRN grn = grnRepository.findById(request.getGrnId())
                .orElseThrow(() -> new ResourceNotFoundException("GRN not found: " + request.getGrnId()));

        if (!grn.getPurchase().getId().equals(purchaseId)) {
            throw new BadRequestException("GRN does not belong to purchase " + purchaseId);
        }

        Long branchId = grn.getBranch().getId();
        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, branchId);

        // 3. Build grnItemId -> GrnItem lookup
        List<GrnItem> allGrnItems = grnItemRepository.findByGrnId(grn.getId());
        Map<Long, GrnItem> grnItemMap = allGrnItems.stream()
                .collect(Collectors.toMap(GrnItem::getId, gi -> gi));

        // 4. Validate every return line
        List<ValidatedReturnLine> validatedLines = new ArrayList<>();
        for (ReturnGrnItemRequest itemReq : request.getItems()) {

            GrnItem grnItem = grnItemMap.get(itemReq.getGrnItemId());
            if (grnItem == null) {
                throw new BadRequestException(
                        "GRN item id " + itemReq.getGrnItemId() + " does not belong to GRN " + grn.getGrnNo());
            }

            // Units: the request and the stored purchase_return_items.return_qty are in
            // DISPLAY units, the unit the GRN line was entered in (grn_items.qty_unit).
            // grn_items.qty and stock batches are in normalized base units (thousandths:
            // 10 pcs = 10000). Compare display against display here, and normalize only
            // for the stock math below, mixing the two scales is what previously let
            // returns deduct 1/1000th of the stock and pass a ~1000x-too-loose cap.
            Item item = grnItem.getItem();
            int alreadyReturned = purchaseReturnItemRepository
                    .sumReturnedQtyByGrnItemId(grnItem.getId());
            // Free (FOC) units were received into stock too, so they are returnable.
            // Refunds use the line's diluted cost price, so returning everything
            // (paid + free) refunds exactly the net amount paid, never more.
            BigDecimal displayFreeQty = grnItem.getDisplayFreeQty() == null
                    ? BigDecimal.ZERO : grnItem.getDisplayFreeQty();
            BigDecimal maxReturnable = grnItem.getDisplayQty()
                    .add(displayFreeQty)
                    .subtract(BigDecimal.valueOf(alreadyReturned));

            if (maxReturnable.signum() <= 0) {
                throw new BadRequestException(
                        "Item '" + item.getName() + "' has already been fully returned");
            }
            if (BigDecimal.valueOf(itemReq.getReturnQty()).compareTo(maxReturnable) > 0) {
                throw new BadRequestException(
                        "Return qty " + itemReq.getReturnQty()
                                + " exceeds returnable qty " + maxReturnable.stripTrailingZeros().toPlainString()
                                + " for item '" + item.getName() + "'");
            }

            int normalizedReturnQty = QuantityConversionUtil.normalizeQuantity(
                    item.getItemType(),
                    item.getDefaultUnit(),
                    BigDecimal.valueOf(itemReq.getReturnQty()),
                    QuantityConversionUtil.isMeasuredItem(item.getItemType())
                            ? grnItem.getQtyUnit()
                            : QuantityConversionUtil.primaryDisplayUnit(item));

            // Cannot return if stock was already sold/consumed beyond what remains
            checkStockAvailableForReturn(grnItem, normalizedReturnQty, branchId, grn.getGrnNo());

            // costPrice is per PRIMARY unit (pc / kg / l). calculateActualAmount divides
            // by the base-unit factor, which also fixes lines entered in G or ML being
            // refunded at 1000x what was paid.
            BigDecimal refundLine = roundMoney(
                    QuantityConversionUtil.calculateActualAmount(item, grnItem.getCostPrice(), normalizedReturnQty));
            validatedLines.add(new ValidatedReturnLine(
                    grnItem, itemReq.getReturnQty(), normalizedReturnQty, refundLine));
        }

        // 5. Total return amount
        BigDecimal totalReturn = roundMoney(
                validatedLines.stream()
                        .map(l -> l.returnLineAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        // 6. Generate debit note number  e.g. DBN-42-R1
        long existingCount = purchaseReturnRepository.countByPurchaseId(purchaseId);
        String debitNoteNo = "DBN-" + purchaseId + "-R" + (existingCount + 1);
        if (purchaseReturnRepository.existsByDebitNoteNo(debitNoteNo)) {
            debitNoteNo = debitNoteNo + "-" + System.currentTimeMillis();
        }

        // 7. Persist PurchaseReturn header
        PurchaseReturn purchaseReturn = PurchaseReturn.builder()
                .debitNoteNo(debitNoteNo)
                .purchaseId(purchaseId)
                .purchaseInvoiceNo(purchase.getInvoiceNo())
                .supplierId(purchase.getSupplier().getId())
                .grnId(grn.getId())
                .branchId(branchId)
                .processedByUserId(user.getId())
                .status(ReturnStatus.COMPLETED)
                .totalReturnAmount(totalReturn)
                .reason(request.getReason().trim())
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .build();

        PurchaseReturn savedReturn = purchaseReturnRepository.save(purchaseReturn);

        // 8. Persist return items + deduct stock
        List<PurchaseReturnItem> savedItems = new ArrayList<>();
        for (ValidatedReturnLine line : validatedLines) {
            boolean stockDeducted = deductStockForReturnItem(
                    line.grnItem, line.normalizedReturnQty, branchId, grn.getGrnNo());

            PurchaseReturnItem returnItem = PurchaseReturnItem.builder()
                    .purchaseReturnId(savedReturn.getId())
                    .grnItemId(line.grnItem.getId())
                    .itemId(line.grnItem.getItem().getId())
                    .itemName(line.grnItem.getItem().getName())
                    .barcode(line.grnItem.getItem().getBarcode())
                    .returnQty(line.returnQty)
                    .costPrice(line.grnItem.getCostPrice())
                    .returnLineAmount(line.returnLineAmount)
                    .stockDeducted(stockDeducted)
                    .build();

            savedItems.add(purchaseReturnItemRepository.save(returnItem));
        }

        // 9. Reduce supplier due if purchase had outstanding due
        BigDecimal purchaseDue = purchase.getDueAmount() == null ? BigDecimal.ZERO : purchase.getDueAmount();
        if (purchaseDue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal reduction = totalReturn.min(purchaseDue);
            purchase.setDueAmount(purchaseDue.subtract(reduction).max(BigDecimal.ZERO));
            purchaseRepository.save(purchase);

            Supplier supplier = purchase.getSupplier();
            BigDecimal supplierDue = supplier.getDueAmount() == null ? BigDecimal.ZERO : supplier.getDueAmount();
            supplier.setDueAmount(supplierDue.subtract(reduction).max(BigDecimal.ZERO));
            supplierRepository.save(supplier);
        }

        // 10. Build response
        String processedByUsername = userRepository.findById(user.getId())
                .map(User::getUsername).orElse(null);

        reportCacheInvalidator.returnsChanged();

        return buildResponse(savedReturn, savedItems, purchase.getSupplier().getName(),
                grn.getGrnNo(), grn.getBranch().getName(), processedByUsername);
    }

    // ---------------------------------------------------------------
    // PUBLIC: List all returns for a purchase
    // ---------------------------------------------------------------

    public List<PurchaseReturnResponse> listByPurchase(Long purchaseId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));

        User user = securityUtils.getCurrentUser();
        // Access check: manager can only see if any grn belongs to their branch
        if (!securityUtils.isAdminLike(user)) {
            Long userBranch = securityUtils.requireAssignedBranch(user);
            boolean hasAccess = purchase.getGrnList().stream()
                    .anyMatch(g -> g.getBranch().getId().equals(userBranch));
            if (!hasAccess) throw new BadRequestException("Cannot access this purchase");
        }

        return purchaseReturnRepository.findByPurchaseIdOrderByCreatedAtDesc(purchaseId)
                .stream()
                .map(r -> {
                    List<PurchaseReturnItem> items =
                            purchaseReturnItemRepository.findByPurchaseReturnId(r.getId());
                    String username = userRepository.findById(r.getProcessedByUserId())
                            .map(User::getUsername).orElse(null);
                    GRN grn = grnRepository.findById(r.getGrnId()).orElse(null);
                    String grnNo = grn != null ? grn.getGrnNo() : null;
                    String branchName = grn != null ? grn.getBranch().getName() : null;
                    String supplierName = purchase.getSupplier().getName();
                    return buildResponse(r, items, supplierName, grnNo, branchName, username);
                })
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------
    // PUBLIC: Get by debit note number (for reprint)
    // ---------------------------------------------------------------

    public PurchaseReturnResponse getByDebitNoteNo(String debitNoteNo) {
        PurchaseReturn pr = purchaseReturnRepository.findByDebitNoteNo(debitNoteNo)
                .orElseThrow(() -> new ResourceNotFoundException("Debit note not found: " + debitNoteNo));

        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, pr.getBranchId());

        List<PurchaseReturnItem> items = purchaseReturnItemRepository.findByPurchaseReturnId(pr.getId());
        String username = userRepository.findById(pr.getProcessedByUserId())
                .map(User::getUsername).orElse(null);

        Supplier supplier = supplierRepository.findById(pr.getSupplierId()).orElse(null);
        String supplierName = supplier != null ? supplier.getName() : null;

        GRN grn = grnRepository.findById(pr.getGrnId()).orElse(null);
        String grnNo = grn != null ? grn.getGrnNo() : null;
        String branchName = grn != null ? grn.getBranch().getName() : null;

        return buildResponse(pr, items, supplierName, grnNo, branchName, username);
    }

    // ---------------------------------------------------------------
    // PRIVATE: Stock deduction logic
    //   Find the batch created by this GRN (batch code = GRN-{grnNo}-{itemId}-{idx})
    //   and deduct returnQty from it
    // ---------------------------------------------------------------

    // Both quantities below are in NORMALIZED base units, same scale as
    // StockBatch.quantity, never the display units the caller typed.
    private void checkStockAvailableForReturn(GrnItem grnItem, int normalizedReturnQty,
                                               Long branchId, String grnNo) {
        Item item = grnItem.getItem();
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            return; // no physical stock to check
        }

        // Find batch created by this GRN for this item
        String batchPrefix = "GRN-" + grnNo + "-" + item.getId() + "-";
        List<StockBatch> batches = stockBatchRepository
                .findByBranchIdAndBatchCodeStartingWith(branchId, batchPrefix);

        if (batches.isEmpty()) return; // batch was deleted (already fully consumed). Allow return, mark not deducted

        int availableInBatches = batches.stream()
                .mapToInt(b -> b.getQuantity() == null ? 0 : b.getQuantity())
                .sum();

        if (availableInBatches < normalizedReturnQty) {
            throw new BadRequestException(
                    "Cannot return this quantity of '" + item.getName()
                            + "'. Only "
                            + QuantityConversionUtil.toDisplayQuantity(item, availableInBatches).stripTrailingZeros().toPlainString()
                            + " remain in stock (rest have been sold). "
                            + "Return only the unsold quantity.");
        }
    }

    private boolean deductStockForReturnItem(GrnItem grnItem, int normalizedReturnQty,
                                              Long branchId, String grnNo) {
        Item item = grnItem.getItem();
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            return false;
        }

        String batchPrefix = "GRN-" + grnNo + "-" + item.getId() + "-";
        List<StockBatch> batches = stockBatchRepository
                .findByBranchIdAndBatchCodeStartingWith(branchId, batchPrefix);

        if (batches.isEmpty()) {
            return false; // batch already consumed. Cannot deduct
        }

        // Deduct from batches FIFO
        int remaining = normalizedReturnQty;
        for (StockBatch batch : batches) {
            if (remaining <= 0) break;
            int available = batch.getQuantity() == null ? 0 : batch.getQuantity();
            int deduct = Math.min(remaining, available);
            batch.setQuantity(available - deduct);
            stockBatchRepository.save(batch);
            remaining -= deduct;
        }

        return true;
    }

    // ---------------------------------------------------------------
    // PRIVATE: Build response
    // ---------------------------------------------------------------

    private PurchaseReturnResponse buildResponse(PurchaseReturn pr, List<PurchaseReturnItem> items,
                                                  String supplierName, String grnNo,
                                                  String branchName, String processedByUsername) {
        List<PurchaseReturnItemResponse> itemResponses = items.stream()
                .map(i -> PurchaseReturnItemResponse.builder()
                        .id(i.getId())
                        .grnItemId(i.getGrnItemId())
                        .itemId(i.getItemId())
                        .itemName(i.getItemName())
                        .barcode(i.getBarcode())
                        .returnQty(i.getReturnQty())
                        .costPrice(i.getCostPrice())
                        .returnLineAmount(i.getReturnLineAmount())
                        .stockDeducted(i.isStockDeducted())
                        .build())
                .collect(Collectors.toList());

        return PurchaseReturnResponse.builder()
                .id(pr.getId())
                .debitNoteNo(pr.getDebitNoteNo())
                .purchaseId(pr.getPurchaseId())
                .purchaseInvoiceNo(pr.getPurchaseInvoiceNo())
                .supplierId(pr.getSupplierId())
                .supplierName(supplierName)
                .grnId(pr.getGrnId())
                .grnNo(grnNo)
                .branchId(pr.getBranchId())
                .branchName(branchName)
                .processedByUsername(processedByUsername)
                .status(pr.getStatus())
                .totalReturnAmount(pr.getTotalReturnAmount())
                .reason(pr.getReason())
                .note(pr.getNote())
                .createdAt(pr.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    // Carries validated data between validation and persistence.
    // returnQty is in display units (what is persisted and shown);
    // normalizedReturnQty is in base units (what stock math uses).
    private record ValidatedReturnLine(
            GrnItem grnItem,
            int returnQty,
            int normalizedReturnQty,
            BigDecimal returnLineAmount) {
    }
}
