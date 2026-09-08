package com.chala.posapp.service;

import com.chala.posapp.dto.returns.CreateReturnRequest;
import com.chala.posapp.dto.returns.OrderReturnItemResponse;
import com.chala.posapp.dto.returns.OrderReturnResponse;
import com.chala.posapp.dto.returns.ReturnItemRequest;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.util.SecurityUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class OrderReturnService {

    private final OrderRepository               orderRepository;
    private final OrderItemRepository           orderItemRepository;
    private final OrderItemStockUsageRepository orderItemStockUsageRepository;
    private final OrderReturnRepository         orderReturnRepository;
    private final OrderReturnItemRepository     orderReturnItemRepository;
    private final SecurityUtils                 securityUtils;
    private final CustomerRepository            customerRepository;
    private final StockBatchRepository          stockBatchRepository;
    private final ItemRepository                itemRepository;
    private final CashShiftRepository           cashShiftRepository;
    private final WarrantyRepository            warrantyRepository;
    private final UserRepository                userRepository;
    private final ReportCacheInvalidator        reportCacheInvalidator;
    private final PromotionRedemptionService    promotionRedemptionService;
    private final LoyaltyService                loyaltyService;

    // ---------------------------------------------------------------
    // Helpers — mirrors OrderService pattern exactly
    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead
    // ---------------------------------------------------------------

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureBranchAccess(User user, Long branchId) {
        if (securityUtils.isAdminLike(user)) return;
        Long userBranchId = securityUtils.requireAssignedBranch(user);
        if (!userBranchId.equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    private double roundMoney(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------
    // PUBLIC: Process a partial return
    // ---------------------------------------------------------------

    @Transactional
    public OrderReturnResponse processReturn(String invoiceNo, CreateReturnRequest request) {

        // 1. Load & validate order
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + invoiceNo));

        if (order.getStatus() == OrderStatus.CANCELED) {
            throw new BadRequestException("Cannot process a return for a canceled order");
        }

        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, order.getBranchId());

        // Build orderItemId -> OrderItem lookup map
        List<OrderItem> allOrderItems = orderItemRepository.findByOrderId(order.getId());
        Map<Long, OrderItem> orderItemMap = allOrderItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, oi -> oi));

        // Validate refund method
        String refundMethod = request.getRefundMethod().trim().toUpperCase();
        List<String> validMethods = List.of("CASH", "BANK", "CARD", "STORE_CREDIT");
        if (!validMethods.contains(refundMethod)) {
            throw new BadRequestException(
                    "Invalid refund method: " + refundMethod + ". Must be one of: " + validMethods);
        }

        // 2. Validate every return line
        List<ValidatedReturnLine> validatedLines = new ArrayList<>();
        for (ReturnItemRequest itemReq : request.getItems()) {

            OrderItem originalItem = orderItemMap.get(itemReq.getOrderItemId());
            if (originalItem == null) {
                throw new BadRequestException(
                        "Order item id " + itemReq.getOrderItemId()
                                + " does not belong to order " + invoiceNo);
            }

            int alreadyReturned = orderReturnItemRepository
                    .sumReturnedQtyByOrderItemId(originalItem.getId());
            int maxReturnable = originalItem.getQty() - alreadyReturned;

            if (maxReturnable <= 0) {
                throw new BadRequestException(
                        "Item '" + originalItem.getItemName() + "' has already been fully returned");
            }
            if (itemReq.getReturnQty() > maxReturnable) {
                throw new BadRequestException(
                        "Return qty " + itemReq.getReturnQty()
                                + " exceeds returnable qty " + maxReturnable
                                + " for item '" + originalItem.getItemName() + "'");
            }

            double ticketLine = roundMoney(itemReq.getReturnQty() * originalItem.getFinalUnitPrice());
            validatedLines.add(new ValidatedReturnLine(originalItem, itemReq.getReturnQty(), ticketLine));
        }

        // 3. Calculate the refund — what the customer actually handed over for these goods.
        //
        // finalUnitPrice x qty is the line's ticket price, not what was charged for it. A
        // bill-level discount came off the whole sale, and points the customer spent paid for
        // part of it. Refunding the ticket price hands back money that never arrived: on a
        // 1,180 sale settled with 200 in cash and 970 in points, returning everything paid out
        // 1,180 in cash — the shop lost 980 on a return of its own goods.
        //
        // So the ticket is split the way the sale was settled. The cash share is refunded; the
        // points share goes back as points, below.
        double ticketTotal = roundMoney(
                validatedLines.stream().mapToDouble(l -> l.refundLineAmount).sum());
        double saleValue = order.getSubTotal();
        double pointsCharged = order.getLoyaltyDiscountAmount() == null
                ? 0.0 : order.getLoyaltyDiscountAmount().doubleValue();
        // A sale with no value to divide by can only refund what the lines say.
        double cashShare = saleValue > 0 ? order.getGrandTotal() / saleValue : 1.0;
        double pointsShare = saleValue > 0 ? pointsCharged / saleValue : 0.0;

        validatedLines.replaceAll(line -> new ValidatedReturnLine(
                line.originalItem, line.returnQty, roundMoney(line.refundLineAmount * cashShare)));

        double totalRefund = roundMoney(
                validatedLines.stream().mapToDouble(l -> l.refundLineAmount).sum());
        // What the points paid for, in money. Converted to points and handed back below.
        double pointsValueBack = roundMoney(ticketTotal * pointsShare);

        // 4. Generate return number  e.g. RTN-2026-06-B1-000042-R1
        long existingCount = orderReturnRepository.countByOriginalOrderId(order.getId());
        String returnNo = "RTN-" + invoiceNo.substring(4) + "-R" + (existingCount + 1);
        if (orderReturnRepository.existsByReturnNo(returnNo)) {
            returnNo = returnNo + "-" + System.currentTimeMillis();
        }

        // 5. Persist OrderReturn header
        OrderReturn orderReturn = OrderReturn.builder()
                .returnNo(returnNo)
                .originalOrderId(order.getId())
                .originalInvoiceNo(order.getInvoiceNo())
                .branchId(order.getBranchId())
                .cashierUserId(user.getId())
                .customerId(order.getCustomerId())
                .status(ReturnStatus.COMPLETED)
                .refundMethod(refundMethod)
                .totalRefundAmount(totalRefund)
                .reason(request.getReason().trim())
                .cashierNote(request.getCashierNote() != null
                        ? request.getCashierNote().trim() : null)
                .build();

        OrderReturn savedReturn = orderReturnRepository.save(orderReturn);

        // 6. Persist return items + reverse stock
        List<OrderReturnItem> savedReturnItems = new ArrayList<>();
        for (ValidatedReturnLine line : validatedLines) {

            boolean stockReversed = reverseStockForReturnItem(
                    line.originalItem, line.returnQty,
                    order.getBranchId(), order.isOfflineImported());

            OrderReturnItem returnItem = OrderReturnItem.builder()
                    .orderReturnId(savedReturn.getId())
                    .orderItemId(line.originalItem.getId())
                    .itemId(line.originalItem.getItemId())
                    .itemName(line.originalItem.getItemName())
                    .barcode(line.originalItem.getBarcode())
                    .returnQty(line.returnQty)
                    .unitPrice(line.originalItem.getUnitPrice())
                    .finalUnitPrice(line.originalItem.getFinalUnitPrice())
                    .refundLineAmount(line.refundLineAmount)
                    .stockReversed(stockReversed)
                    .build();

            savedReturnItems.add(orderReturnItemRepository.save(returnItem));
        }

        // 6b. Give back the promotions and points this return undid.
        //
        // Returning goods used to reverse neither: a capped campaign with a normal rate of
        // returns exhausted its cap early and nothing said why, and points earned on goods that
        // came back stayed in the customer's balance.
        //
        // A return that empties the order is the sale being undone, so it takes the same path a
        // cancellation does — the redemption count is released too. Anything less is
        // proportional: the money comes off the budget, but the promotion was still used on the
        // order for whatever the customer kept.
        boolean fullyReturned = allOrderItems.stream().allMatch(item ->
                orderReturnItemRepository.sumReturnedQtyByOrderItemId(item.getId()) >= item.getQty());

        // The whole-order path is for a single return that undoes the whole sale. A return that
        // merely completes an order after earlier partials is still a partial: the whole-order
        // path reverses every ledger row, including the reversal rows those earlier returns
        // wrote, and would hand back points already handed back and release budget already
        // released. The proportional path gets the remainder right and its caps hold.
        boolean undoesWholeSale = fullyReturned && existingCount == 0;

        LoyaltyService.ReversalOutcome pointsMoved;
        if (undoesWholeSale) {
            promotionRedemptionService.reverseForOrder(order.getId(), order.getId());
            pointsMoved = loyaltyService.reverseForOrder(order.getId(), user.getId());
        } else {
            List<PromotionRedemptionService.ReturnedLine> returnedShares = validatedLines.stream()
                    .filter(line -> line.originalItem.getQty() > 0)
                    .map(line -> new PromotionRedemptionService.ReturnedLine(
                            line.originalItem.getId(),
                            BigDecimal.valueOf(line.returnQty)
                                    .divide(BigDecimal.valueOf(line.originalItem.getQty()), 6, RoundingMode.HALF_UP)))
                    .toList();
            promotionRedemptionService.reverseForReturn(
                    order.getId(), savedReturn.getId(), user.getId(), returnedShares);

            // Earned points come back in proportion to the share of the sale returned — which
            // is the share of its value, not of its cash, or a sale settled mostly in points
            // would claw back several times what it awarded.
            pointsMoved = loyaltyService.clawBackForReturn(order.getId(), user.getId(),
                    saleValue > 0
                            ? BigDecimal.valueOf(ticketTotal).divide(BigDecimal.valueOf(saleValue), 6, RoundingMode.HALF_UP)
                            : BigDecimal.ONE,
                    BigDecimal.valueOf(pointsValueBack));
        }

        // Kept on the return so its receipt can account for the points as well as the money —
        // the half of the transaction the customer cannot check for themselves.
        if (!pointsMoved.movedNothing() || pointsValueBack > 0) {
            savedReturn.setLoyaltyPointsTakenBack(pointsMoved.takenBack());
            savedReturn.setLoyaltyPointsGivenBack(pointsMoved.givenBack());
            savedReturn.setLoyaltyPointsBalance(pointsMoved.balanceAfter());
            savedReturn.setLoyaltyValueReturned(BigDecimal.valueOf(pointsValueBack));
            savedReturn = orderReturnRepository.save(savedReturn);
        }

        // 7. Adjust credit customer due for STORE_CREDIT refund
        if ("STORE_CREDIT".equals(refundMethod)
                && order.getOrderType() == OrderType.CREDIT
                && order.getCustomerId() != null) {
            customerRepository.findById(order.getCustomerId()).ifPresent(customer -> {
                double newDue = roundMoney(customer.getDueAmount() - totalRefund);
                customer.setDueAmount(Math.max(0.0, newDue));
                customerRepository.save(customer);
            });
        }

        // 8. Reverse open shift cash for CASH refund
        if ("CASH".equals(refundMethod)) {
            reverseShiftCash(order, totalRefund, user.getId());
        }

        // 9. Void warranties for returned items only (not entire order)
        voidWarrantiesForReturnedItems(order.getId(), validatedLines);

        // 10. Build and return response
        String cashierName  = userRepository.findById(user.getId())
                .map(User::getUsername).orElse(null);
        String customerName = resolveCustomerName(order.getCustomerId());
        reportCacheInvalidator.returnsChanged();

        return buildResponse(savedReturn, savedReturnItems, cashierName, customerName);
    }

    // ---------------------------------------------------------------
    // PUBLIC: List all returns for an invoice
    // ---------------------------------------------------------------

    public List<OrderReturnResponse> listByInvoice(String invoiceNo) {
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + invoiceNo));

        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, order.getBranchId());

        return orderReturnRepository
                .findByOriginalOrderIdOrderByCreatedAtDesc(order.getId())
                .stream()
                .map(r -> {
                    List<OrderReturnItem> items =
                            orderReturnItemRepository.findByOrderReturnId(r.getId());
                    String cashierName  = userRepository.findById(r.getCashierUserId())
                            .map(User::getUsername).orElse(null);
                    String customerName = resolveCustomerName(r.getCustomerId());
                    return buildResponse(r, items, cashierName, customerName);
                })
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------
    // PUBLIC: Get single return by return number (for reprint)
    // ---------------------------------------------------------------

    public OrderReturnResponse getByReturnNo(String returnNo) {
        OrderReturn orderReturn = orderReturnRepository.findByReturnNo(returnNo)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found: " + returnNo));

        User user = securityUtils.getCurrentUser();
        ensureBranchAccess(user, orderReturn.getBranchId());

        List<OrderReturnItem> items =
                orderReturnItemRepository.findByOrderReturnId(orderReturn.getId());
        String cashierName  = userRepository.findById(orderReturn.getCashierUserId())
                .map(User::getUsername).orElse(null);
        String customerName = resolveCustomerName(orderReturn.getCustomerId());
        return buildResponse(orderReturn, items, cashierName, customerName);
    }

    // ---------------------------------------------------------------
    // PRIVATE: Stock reversal — mirrors cancelOrder() in OrderService
    //   Case 1: usage rows exist  -> proportional restore across batches
    //   Case 2: offline-imported, no batchId -> skip
    //   Case 3: single batchId fallback -> restore directly
    //   SERVICE / RECIPE -> skip (no physical stock)
    // ---------------------------------------------------------------

    private boolean reverseStockForReturnItem(OrderItem originalItem, int returnQty,
                                               Long branchId, boolean offlineImported) {
        Item item = itemRepository.findById(originalItem.getItemId()).orElse(null);
        if (item != null
                && (item.getItemType() == ItemType.SERVICE
                || item.getItemType() == ItemType.RECIPE)) {
            return false;
        }

        List<OrderItemStockUsage> usages =
                orderItemStockUsageRepository.findByOrderItemId(originalItem.getId());

        if (!usages.isEmpty()) {
            int originalQty = originalItem.getQty();
            Map<Long, StockBatch> batchesToSave = new LinkedHashMap<>();

            for (OrderItemStockUsage usage : usages) {
                int restoreQty = (int) Math.ceil(
                        (double) returnQty / originalQty * usage.getQuantity());
                restoreQty = Math.min(restoreQty, usage.getQuantity());
                if (restoreQty <= 0) continue;

                StockBatch batch = stockBatchRepository.findById(usage.getBatchId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Stock batch not found for return stock restoration"));
                batch.setQuantity(batch.getQuantity() + restoreQty);
                batchesToSave.put(batch.getId(), batch);
            }
            if (!batchesToSave.isEmpty()) {
                stockBatchRepository.saveAll(batchesToSave.values());
            }
            return true;

        } else if (offlineImported && originalItem.getBatchId() == null) {
            return false;

        } else if (originalItem.getBatchId() != null) {
            StockBatch batch = stockBatchRepository.findById(originalItem.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Original batch not found for return: " + originalItem.getItemName()));
            batch.setQuantity(batch.getQuantity() + returnQty);
            stockBatchRepository.save(batch);
            return true;
        }

        return false;
    }

    // ---------------------------------------------------------------
    // PRIVATE: Reverse open shift cash sales
    // Uses the processing cashier's open shift (mirrors reverseCashSaleFromOpenShift)
    // ---------------------------------------------------------------

    private void reverseShiftCash(Order order, double refundAmount, Long processingUserId) {
        if (refundAmount <= 0) return;
        cashShiftRepository
                .findByBranchIdAndCashierUserIdAndStatus(
                        order.getBranchId(), processingUserId, ShiftStatus.OPEN)
                .ifPresent(shift -> {
                    double current = shift.getCashSales() == null ? 0.0 : shift.getCashSales();
                    shift.setCashSales(Math.max(0.0, current - refundAmount));
                    cashShiftRepository.save(shift);
                });
    }

    // ---------------------------------------------------------------
    // PRIVATE: Void warranties only for returned items
    // Unlike cancelOrder() which voids ALL warranties on the order
    // ---------------------------------------------------------------

    private void voidWarrantiesForReturnedItems(Long orderId,
                                                 List<ValidatedReturnLine> returnedLines) {
        Set<Long> returnedOrderItemIds = returnedLines.stream()
                .map(l -> l.originalItem.getId())
                .collect(Collectors.toSet());

        List<Warranty> toVoid = warrantyRepository.findByOrderId(orderId).stream()
                .filter(w -> returnedOrderItemIds.contains(w.getOrderItemId()))
                .filter(w -> w.getStatus() == WarrantyStatus.ACTIVE)
                .collect(Collectors.toList());

        if (!toVoid.isEmpty()) {
            toVoid.forEach(w -> w.setStatus(WarrantyStatus.VOID));
            warrantyRepository.saveAll(toVoid);
        }
    }

    private String resolveCustomerName(Long customerId) {
        if (customerId == null) return "Walk-in Customer";
        return customerRepository.findById(customerId)
                .map(Customer::getName).orElse("Unknown Customer");
    }

    private OrderReturnResponse buildResponse(OrderReturn r, List<OrderReturnItem> items,
                                               String cashierName, String customerName) {
        List<OrderReturnItemResponse> itemResponses = items.stream()
                .map(i -> OrderReturnItemResponse.builder()
                        .id(i.getId())
                        .orderItemId(i.getOrderItemId())
                        .itemId(i.getItemId())
                        .itemName(i.getItemName())
                        .barcode(i.getBarcode())
                        .returnQty(i.getReturnQty())
                        .unitPrice(i.getUnitPrice())
                        .finalUnitPrice(i.getFinalUnitPrice())
                        .refundLineAmount(i.getRefundLineAmount())
                        .stockReversed(i.isStockReversed())
                        .build())
                .collect(Collectors.toList());

        return OrderReturnResponse.builder()
                .id(r.getId())
                .returnNo(r.getReturnNo())
                .originalOrderId(r.getOriginalOrderId())
                .originalInvoiceNo(r.getOriginalInvoiceNo())
                .branchId(r.getBranchId())
                .cashierName(cashierName)
                .customerId(r.getCustomerId())
                .customerName(customerName)
                .status(r.getStatus())
                .refundMethod(r.getRefundMethod())
                .totalRefundAmount(r.getTotalRefundAmount())
                .reason(r.getReason())
                .cashierNote(r.getCashierNote())
                .createdAt(r.getCreatedAt())
                .loyaltyPointsTakenBack(r.getLoyaltyPointsTakenBack())
                .loyaltyPointsGivenBack(r.getLoyaltyPointsGivenBack())
                .loyaltyPointsBalance(r.getLoyaltyPointsBalance())
                .loyaltyValueReturned(r.getLoyaltyValueReturned() == null
                        ? 0.0 : r.getLoyaltyValueReturned().doubleValue())
                .items(itemResponses)
                .build();
    }

    // Carries validated data cleanly between validation and persistence steps
    private record ValidatedReturnLine(
            OrderItem originalItem,
            int returnQty,
            double refundLineAmount) {
    }
}
