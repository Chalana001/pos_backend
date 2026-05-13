package com.chala.posapp.service;

import com.chala.posapp.dto.CancelPurchaseRequest;
import com.chala.posapp.dto.CreatePurchaseRequest;
import com.chala.posapp.dto.PurchaseResponse;
import com.chala.posapp.dto.branch.BranchPurchaseRequest;
import com.chala.posapp.dto.grn.GrnItemRequest;
import com.chala.posapp.dto.grn.GrnItemResponse;
import com.chala.posapp.dto.grn.GrnResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {
    private static final DateTimeFormatter PURCHASE_INVOICE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final PurchaseRepository purchaseRepository;
    private final GrnRepository grnRepository;
    private final GrnItemRepository grnItemRepository;
    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final GrnNumberService grnNumberService;
    private final UserRepository userRepository;

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

        Purchase purchase = Purchase.builder()
                .supplier(supplier)
                .invoiceNo(invoiceNo)
                .paymentMethod(normalizePaymentMethod(request.getPaymentMethod()))
                .createdAt(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO)
                .status(PurchaseStatus.COMPLETED)
                .build();
        Purchase savedPurchase = purchaseRepository.save(purchase);

        BigDecimal grandTotal = BigDecimal.ZERO;
        List<GrnResponse> grnResponseList = new ArrayList<>();

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
                index++;
                Item item = itemRepository.findById(itemReq.getItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

                // ✅ සර්විස් අයිටම් එකක් GRN එකට දාන්න හැදුවොත් Block කරනවා
                if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
                    throw new BadRequestException("Only stock-tracked grocery items can be purchased or added to GRN. Item: " + item.getName());
                }

                item.setCostPrice(itemReq.getCostPrice());
                item.setSellingPrice(itemReq.getSellingPrice());
                itemRepository.save(item);

                // ✅ item.isWeightItem() වෙනුවට item.getItemType() පාවිච්චි කිරීම
                int normalizedQty = QuantityConversionUtil.normalizeQuantity(
                        item.getItemType(),
                        item.getDefaultUnit(),
                        itemReq.getQty(),
                        item.getItemType() == ItemType.WEIGHT ? MeasurementUnit.KG : MeasurementUnit.PCS
                );

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
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .batchCode(batchCode)
                        .receivedAt(LocalDateTime.now())
                        .expireDate(expiry)
                        .build();
                stockBatchRepository.save(batch);

                BigDecimal lineTotal = QuantityConversionUtil.calculateActualAmount(item, itemReq.getCostPrice(), normalizedQty);

                GrnItem grnItem = GrnItem.builder()
                        .grn(savedGrn)
                        .item(item)
                        .qty(normalizedQty)
                        .displayQty(itemReq.getQty().stripTrailingZeros())
                        .qtyUnit(item.getItemType() == ItemType.WEIGHT ? MeasurementUnit.KG : MeasurementUnit.PCS)
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .amount(lineTotal)
                        .build();
                grnItems.add(grnItem);

                grnTotal = grnTotal.add(lineTotal);

                itemResponses.add(GrnItemResponse.builder()
                        .itemId(item.getId())
                        .itemName(item.getName())
                        .barcode(item.getBarcode())
                        .qty(grnItem.getDisplayQty())
                        .qtyUnit(grnItem.getQtyUnit())
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .lineTotal(lineTotal)
                        .build());
            }

            grnItemRepository.saveAll(grnItems);
            savedGrn.setTotalAmount(grnTotal);
            grnRepository.save(savedGrn);
            grandTotal = grandTotal.add(grnTotal);

            grnResponseList.add(GrnResponse.builder()
                    .id(savedGrn.getId())
                    .grnNo(savedGrn.getGrnNo())
                    .branchName(branch.getName())
                    .supplierName(supplier.getName())
                    .totalAmount(grnTotal)
                    .receivedAt(savedGrn.getReceivedAt())
                    .note(savedGrn.getNote())
                    .items(itemResponses)
                    .build());
        }

        savedPurchase.setGrandTotal(grandTotal);
        if (discountAmount.compareTo(grandTotal) > 0) {
            throw new BadRequestException("Discount amount cannot exceed purchase total");
        }
        BigDecimal netTotal = grandTotal.subtract(discountAmount);
        if (requestedPaidAmount.compareTo(netTotal) > 0) {
            throw new BadRequestException("Paid amount cannot exceed purchase total");
        }
        BigDecimal dueAmount = netTotal.subtract(requestedPaidAmount);
        savedPurchase.setDiscountAmount(discountAmount);
        savedPurchase.setGrandTotal(netTotal);
        savedPurchase.setPaidAmount(requestedPaidAmount);
        savedPurchase.setDueAmount(dueAmount);
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

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }
}
