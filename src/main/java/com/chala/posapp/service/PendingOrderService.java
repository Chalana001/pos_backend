package com.chala.posapp.service;

import com.chala.posapp.dto.dining.PendingOrderItemResponse;
import com.chala.posapp.dto.dining.PendingOrderResponse;
import com.chala.posapp.dto.dining.PendingOrderSaveRequest;
import com.chala.posapp.dto.order.OrderItemRequest;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.DiningTable;
import com.chala.posapp.entity.DiningTableStatus;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.PendingOrder;
import com.chala.posapp.entity.PendingOrderItem;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SaleMode;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.DiningTableRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.PendingOrderItemRepository;
import com.chala.posapp.repository.PendingOrderRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingOrderService {

    private final PendingOrderRepository pendingOrderRepository;
    private final PendingOrderItemRepository pendingOrderItemRepository;
    private final DiningTableRepository diningTableRepository;
    private final ItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final SecurityUtils securityUtils;
    private final BranchServiceItemRepository branchServiceItemRepository;
    private final AppConfigurationService appConfigurationService;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureBranchAccess(User user, Long branchId) {
        if (securityUtils.isAdminLike(user)) {
            return;
        }

        Long userBranchId = securityUtils.requireAssignedBranch(user);
        if (!userBranchId.equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    @Transactional
    public PendingOrderResponse saveForTable(Long tableId, PendingOrderSaveRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BadRequestException("Pending order items required");
        }

        User user = securityUtils.getCurrentUser();
        DiningTable table = diningTableRepository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        validateDineInEnabled(table.getBranchId());
        ensureBranchAccess(user, table.getBranchId());

        List<PendingOrderItem> itemsToSave = new ArrayList<>();
        double subTotal = 0.0;

        for (OrderItemRequest itemRequest : request.getItems()) {
            validateWarrantySelection(itemRequest, user.getRole(), table.getBranchId());
            Item item = itemRepository.findById(itemRequest.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemRequest.getItemId()));

            if (!item.isActive()) {
                throw new BadRequestException("Item is inactive: " + item.getBarcode());
            }
            if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), table.getBranchId())) {
                throw new BadRequestException(item.getItemType().name() + " items are disabled in app configuration");
            }
            if (item.getItemType() == ItemType.SERVICE
                    && !branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(table.getBranchId(), item.getId())) {
                throw new BadRequestException("Service item is not assigned to this branch: " + item.getName());
            }
            int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, itemRequest.getQty(), itemRequest.getQtyUnit());
            DiscountType discountType = itemRequest.getDiscountType() == null ? DiscountType.NONE : itemRequest.getDiscountType();
            double discountValue = itemRequest.getDiscountValue();
            double finalUnitPrice = calculateFinalUnitPrice(itemRequest.getUnitPrice(), discountType, discountValue);
            double lineTotal = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(finalUnitPrice), normalizedQty).doubleValue();

            itemsToSave.add(PendingOrderItem.builder()
                    .pendingOrderId(null)
                    .itemId(item.getId())
                    .batchId(itemRequest.getBatchId())
                    .displayQty(itemRequest.getQty().stripTrailingZeros())
                    .qtyUnit(resolveQtyUnit(item, itemRequest))
                    .unitPrice(itemRequest.getUnitPrice())
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .warrantyLabel(itemRequest.getWarrantyLabel())
                    .warrantyPeriodValue(itemRequest.getWarrantyPeriodValue())
                    .warrantyPeriodUnit(itemRequest.getWarrantyPeriodUnit())
                    .build());

            subTotal += lineTotal;
        }

        double billDiscount = request.getBillDiscount();
        if (billDiscount < 0) billDiscount = 0;
        if (billDiscount > subTotal) billDiscount = subTotal;
        double grandTotal = subTotal - billDiscount;

        PendingOrder pendingOrder = pendingOrderRepository.findByTableId(tableId)
                .orElseGet(() -> PendingOrder.builder()
                        .branchId(table.getBranchId())
                        .tableId(table.getId())
                        .cashierUserId(user.getId())
                        .createdAt(LocalDateTime.now())
                        .build());

        pendingOrder.setBranchId(table.getBranchId());
        pendingOrder.setTableId(table.getId());
        pendingOrder.setCashierUserId(user.getId());
        pendingOrder.setCustomerId(request.getCustomerId());
        pendingOrder.setBillDiscount(billDiscount);
        pendingOrder.setSubTotal(subTotal);
        pendingOrder.setGrandTotal(grandTotal);
        pendingOrder.setNote(request.getNote());
        pendingOrder.setUpdatedAt(LocalDateTime.now());

        PendingOrder savedPendingOrder = pendingOrderRepository.save(pendingOrder);
        pendingOrderItemRepository.deleteByPendingOrderId(savedPendingOrder.getId());
        for (PendingOrderItem pendingOrderItem : itemsToSave) {
            pendingOrderItem.setPendingOrderId(savedPendingOrder.getId());
        }
        pendingOrderItemRepository.saveAll(itemsToSave);

        table.setStatus(DiningTableStatus.OCCUPIED);
        diningTableRepository.save(table);

        return buildResponse(savedPendingOrder, table, itemsToSave);
    }

    public PendingOrderResponse getByTable(Long tableId) {
        User user = securityUtils.getCurrentUser();
        PendingOrder pendingOrder = pendingOrderRepository.findByTableId(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Pending order not found"));
        DiningTable table = diningTableRepository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        validateDineInEnabled(table.getBranchId());
        ensureBranchAccess(user, table.getBranchId());
        return buildResponse(pendingOrder, table, pendingOrderItemRepository.findByPendingOrderId(pendingOrder.getId()));
    }

    public List<PendingOrderResponse> listByBranch(Long branchId) {
        validateDineInEnabled(branchId);

        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, branchId);

        return pendingOrderRepository.findByBranchIdOrderByUpdatedAtDesc(branchId).stream()
                .map(pendingOrder -> {
                    DiningTable table = diningTableRepository.findById(pendingOrder.getTableId())
                            .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
                    return buildResponse(pendingOrder, table, pendingOrderItemRepository.findByPendingOrderId(pendingOrder.getId()));
                })
                .toList();
    }

    @Transactional
    public void clearTable(Long tableId) {
        User user = securityUtils.getCurrentUser();
        DiningTable table = diningTableRepository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        validateDineInEnabled(table.getBranchId());
        ensureBranchAccess(user, table.getBranchId());

        pendingOrderRepository.findByTableId(tableId).ifPresent(pendingOrder -> {
            pendingOrderItemRepository.deleteByPendingOrderId(pendingOrder.getId());
            pendingOrderRepository.delete(pendingOrder);
        });

        table.setStatus(DiningTableStatus.AVAILABLE);
        diningTableRepository.save(table);
    }

    private PendingOrderResponse buildResponse(PendingOrder pendingOrder, DiningTable table, List<PendingOrderItem> pendingItems) {
        String customerName = "Walk-in Customer";
        if (pendingOrder.getCustomerId() != null) {
            customerName = customerRepository.findById(pendingOrder.getCustomerId())
                    .map(Customer::getName)
                    .orElse("Unknown Customer");
        }

        List<PendingOrderItemResponse> items = pendingItems.stream()
                .map(this::mapItem)
                .toList();

        return PendingOrderResponse.builder()
                .id(pendingOrder.getId())
                .branchId(pendingOrder.getBranchId())
                .tableId(table.getId())
                .tableName(table.getTableName())
                .cashierUserId(pendingOrder.getCashierUserId())
                .customerId(pendingOrder.getCustomerId())
                .customerName(customerName)
                .saleMode(SaleMode.DINE_IN)
                .subTotal(pendingOrder.getSubTotal())
                .billDiscount(pendingOrder.getBillDiscount())
                .grandTotal(pendingOrder.getGrandTotal())
                .note(pendingOrder.getNote())
                .createdAt(pendingOrder.getCreatedAt())
                .updatedAt(pendingOrder.getUpdatedAt())
                .items(items)
                .build();
    }

    private PendingOrderItemResponse mapItem(PendingOrderItem pendingOrderItem) {
        Item item = itemRepository.findById(pendingOrderItem.getItemId())
                .orElse(null);
        int normalizedQty = item == null ? 0 : QuantityConversionUtil.normalizeSaleQuantity(item, pendingOrderItem.getDisplayQty(), pendingOrderItem.getQtyUnit());
        double finalUnitPrice = calculateFinalUnitPrice(
                pendingOrderItem.getUnitPrice(),
                pendingOrderItem.getDiscountType(),
                pendingOrderItem.getDiscountValue()
        );
        double lineTotal = item == null
                ? 0.0
                : QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(finalUnitPrice), normalizedQty).doubleValue();

        return PendingOrderItemResponse.builder()
                .itemId(pendingOrderItem.getItemId())
                .barcode(item != null ? item.getBarcode() : "UNKNOWN")
                .itemName(item != null ? item.getName() : "Unknown Item")
                .batchId(pendingOrderItem.getBatchId())
                .qty(pendingOrderItem.getDisplayQty())
                .qtyUnit(pendingOrderItem.getQtyUnit())
                .unitPrice(pendingOrderItem.getUnitPrice())
                .discountType(pendingOrderItem.getDiscountType().name())
                .discountValue(pendingOrderItem.getDiscountValue())
                .finalUnitPrice(finalUnitPrice)
                .lineTotal(lineTotal)
                .warrantyLabel(pendingOrderItem.getWarrantyLabel())
                .warrantyPeriodValue(pendingOrderItem.getWarrantyPeriodValue())
                .warrantyPeriodUnit(pendingOrderItem.getWarrantyPeriodUnit())
                .build();
    }

    private double calculateFinalUnitPrice(double unitPrice, DiscountType type, double value) {
        if (type == null || type == DiscountType.NONE) return unitPrice;

        if (type == DiscountType.PERCENT) {
            if (value < 0) value = 0;
            if (value > 100) value = 100;
            return unitPrice - (unitPrice * (value / 100.0));
        }

        if (value < 0) value = 0;
        if (value > unitPrice) value = unitPrice;
        return unitPrice - value;
    }

    private com.chala.posapp.entity.MeasurementUnit resolveQtyUnit(Item item, OrderItemRequest itemRequest) {
        if (QuantityConversionUtil.isMeasuredItem(item.getItemType())) {
            return itemRequest.getQtyUnit() == null ? item.getDefaultUnit() : itemRequest.getQtyUnit();
        }
        if (item.getItemType() == ItemType.SERVICE) {
            return item.getDefaultUnit();
        }
        return item.getDefaultUnit();
    }

    private void validateWarrantySelection(OrderItemRequest itemRequest, Role role, Long branchId) {
        boolean hasLabel = itemRequest.getWarrantyLabel() != null && !itemRequest.getWarrantyLabel().isBlank();
        boolean hasPeriodValue = itemRequest.getWarrantyPeriodValue() != null;
        boolean hasPeriodUnit = itemRequest.getWarrantyPeriodUnit() != null;

        if ((hasLabel || hasPeriodValue || hasPeriodUnit) && !(hasLabel && hasPeriodValue && hasPeriodUnit)) {
            throw new BadRequestException("Warranty selection is incomplete");
        }
        if ((hasLabel || hasPeriodValue || hasPeriodUnit) && !appConfigurationService.isWarrantyAllowedForRole(role, branchId)) {
            throw new BadRequestException("Warranty selection is disabled for this role");
        }
    }

    private void validateDineInEnabled(Long branchId) {
        if (!appConfigurationService.isDineInEnabled(branchId)) {
            throw new BadRequestException("Dine-in is disabled in app configuration");
        }
    }
}
