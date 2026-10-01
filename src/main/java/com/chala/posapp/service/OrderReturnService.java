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
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    // Helpers, mirrors OrderService pattern exactly
    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike(). Use SecurityUtils instead
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

    private double roundMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).doubleValue();
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

        // STORE_CREDIT is a promise this system can only keep on a credit sale: there is no customer
        // wallet or credit-note ledger, so a cash sale refunded as store credit would write the method
        // onto the return and the receipt while recording the credit nowhere. Refuse it here rather
        // than print a slip for money that does not exist. A real store-credit balance is a feature,
        // not a line of code.
        if ("STORE_CREDIT".equals(refundMethod)
                && (order.getOrderType() != OrderType.CREDIT || order.getCustomerId() == null)) {
            throw new BadRequestException(
                    "Store credit can only be refunded on a credit sale. "
                            + "Use cash, bank or card for this sale.");
        }

        // 2. Validate every return line.
        //
        // Units, read carefully, because mixing the two scales is what broke this whole flow.
        //
        // The request, the UI and order_return_items.return_qty are in DISPLAY units: the unit
        // the till showed the cashier (10 pcs, 2 kg). OrderItem.qty, order_item_stock_usages
        // .quantity and stock_batches.quantity are in NORMALIZED base units, thousandths, so
        // 10 pcs is 10000. final_unit_price is per primary display unit, so the money is only
        // right when multiplied by the display quantity.
        //
        // This block used to compare the display figure straight against OrderItem.qty: a
        // return of 10 pcs passed a cap of 9990 and put 10 base units back on the shelf, one
        // hundredth of a piece, which is the "stock only rises by 0.001 after a return" a shop
        // reported. Compare display against display, and normalize once, only for the stock
        // arithmetic. PurchaseReturnService already does this correctly and documents the same
        // bug at length.
        List<ValidatedReturnLine> validatedLines = new ArrayList<>();
        for (ReturnItemRequest itemReq : request.getItems()) {

            OrderItem originalItem = orderItemMap.get(itemReq.getOrderItemId());
            if (originalItem == null) {
                throw new BadRequestException(
                        "Order item id " + itemReq.getOrderItemId()
                                + " does not belong to order " + invoiceNo);
            }

            Item item = itemRepository.findById(originalItem.getItemId()).orElse(null);
            if (item == null) {
                throw new ResourceNotFoundException(
                        "Item not found for return: " + originalItem.getItemName());
            }

            BigDecimal displaySold = displayQtyOf(originalItem);
            BigDecimal alreadyReturned = orZero(orderReturnItemRepository
                    .sumReturnedQtyByOrderItemId(originalItem.getId()));
            BigDecimal maxReturnable = displaySold.subtract(alreadyReturned);

            if (maxReturnable.signum() <= 0) {
                throw new BadRequestException(
                        "Item '" + originalItem.getItemName() + "' has already been fully returned");
            }
            if (itemReq.getReturnQty().compareTo(maxReturnable) > 0) {
                throw new BadRequestException(
                        "Return qty " + itemReq.getReturnQty().stripTrailingZeros().toPlainString()
                                + " exceeds returnable qty "
                                + maxReturnable.stripTrailingZeros().toPlainString()
                                + " for item '" + originalItem.getItemName() + "'");
            }

            // The same display figure in base units: what every stock calculation uses.
            int normalizedReturnQty = QuantityConversionUtil.normalizeSaleQuantity(
                    item,
                    itemReq.getReturnQty(),
                    originalItem.getQtyUnit());

            double ticketLine = roundMoney(itemReq.getReturnQty()
                    .multiply(BigDecimal.valueOf(originalItem.getFinalUnitPrice())));
            validatedLines.add(new ValidatedReturnLine(
                    originalItem, itemReq.getReturnQty(), normalizedReturnQty, ticketLine));
        }

        // 3. Calculate the refund, what the customer actually handed over for these goods.
        //
        // finalUnitPrice x qty is the line's ticket price, not what was charged for it. A
        // bill-level discount came off the whole sale, and points the customer spent paid for
        // part of it. Refunding the ticket price hands back money that never arrived: on a
        // 1,180 sale settled with 200 in cash and 970 in points, returning everything paid out
        // 1,180 in cash, the shop lost 980 on a return of its own goods.
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

        // Each line re-priced to its cash share, with the base quantity carried alongside so
        // the stock call below still gets base units rather than the display figure.
        List<ValidatedReturnLine> pricedLines = validatedLines.stream()
                .map(line -> new ValidatedReturnLine(
                        line.originalItem,
                        line.returnQty,
                        line.normalizedReturnQty,
                        roundMoney(line.refundLineAmount * cashShare)))
                .toList();

        double totalRefund = roundMoney(
                pricedLines.stream().mapToDouble(l -> l.refundLineAmount).sum());
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

        // 6. Persist return items + reverse stock.
        //
        // return_qty keeps the DISPLAY figure: it is what the return slip prints and what the
        // cashier and the customer can both check against the goods on the counter. Only the
        // stock call wants base units, and it is passed them explicitly.
        List<OrderReturnItem> savedReturnItems = new ArrayList<>();
        for (ValidatedReturnLine line : pricedLines) {

            boolean stockReversed = reverseStockForReturnItem(
                    line.originalItem, line.normalizedReturnQty,
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
        // cancellation does, the redemption count is released too. Anything less is
        // proportional: the money comes off the budget, but the promotion was still used on the
        // order for whatever the customer kept.
        //
        // Both sides of this test are DISPLAY quantities: the repository sums what the returns
        // stored, and displayQtyOf is what the till sold.
        boolean fullyReturned = allOrderItems.stream().allMatch(item ->
                orZero(orderReturnItemRepository.sumReturnedQtyByOrderItemId(item.getId()))
                        .compareTo(displayQtyOf(item)) >= 0);

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
            // The share of each line that came back. Both quantities are base units, so the
            // ratio is dimensionless and correct; pairing the display figure with the base one
            // here understated every partial return's share a thousandfold.
            List<PromotionRedemptionService.ReturnedLine> returnedShares = pricedLines.stream()
                    .filter(line -> line.originalItem.getQty() > 0)
                    .map(line -> new PromotionRedemptionService.ReturnedLine(
                            line.originalItem.getId(),
                            BigDecimal.valueOf(line.normalizedReturnQty)
                                    .divide(BigDecimal.valueOf(line.originalItem.getQty()), 6, RoundingMode.HALF_UP)))
                    .toList();
            promotionRedemptionService.reverseForReturn(
                    order.getId(), savedReturn.getId(), user.getId(), returnedShares);

            // The budget share was already given back above. A partial return keeps the
            // promotion because the customer kept goods; when none remain, that rule no longer
            // applies, so release only the remaining redemption count and any code.
            if (fullyReturned) {
                promotionRedemptionService.releaseCountersForCompletedReturn(order.getId());
            }

            // Earned points come back in proportion to the share of the sale returned, which
            // is the share of its value, not of its cash, or a sale settled mostly in points
            // would claw back several times what it awarded.
            pointsMoved = loyaltyService.clawBackForReturn(order.getId(), user.getId(),
                    saleValue > 0
                            ? BigDecimal.valueOf(ticketTotal).divide(BigDecimal.valueOf(saleValue), 6, RoundingMode.HALF_UP)
                            : BigDecimal.ONE,
                    BigDecimal.valueOf(pointsValueBack));
        }

        // Kept on the return so its receipt can account for the points as well as the money,
        // the half of the transaction the customer cannot check for themselves.
        if (!pointsMoved.movedNothing() || pointsValueBack > 0) {
            savedReturn.setLoyaltyPointsTakenBack(pointsMoved.takenBack());
            savedReturn.setLoyaltyPointsGivenBack(pointsMoved.givenBack());
            savedReturn.setLoyaltyPointsBalance(pointsMoved.balanceAfter());
            savedReturn.setLoyaltyValueReturned(BigDecimal.valueOf(pointsValueBack));
            savedReturn = orderReturnRepository.save(savedReturn);
        }

        // 7. Adjust credit customer due for STORE_CREDIT refund. The request-level guard above
        // guarantees this is a credit sale with a customer.
        if ("STORE_CREDIT".equals(refundMethod)) {
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
        voidWarrantiesForReturnedItems(order.getId(), pricedLines);

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

    /**
     * What the till sold this line as, in DISPLAY units. display_qty was not on the earliest
     * rows, so a null there falls back to the base quantity rather than comparing against a
     * null; a sale old enough to have one predates returns anyway.
     */
    private BigDecimal displayQtyOf(OrderItem orderItem) {
        return orderItem.getDisplayQty() == null
                ? BigDecimal.valueOf(orderItem.getQty())
                : orderItem.getDisplayQty();
    }

    /**
     * The repository sums a nullable BigDecimal column. COALESCE covers the empty-result case in
     * query languages that keep it, but a Java null must never flow into the cap arithmetic.
     */
    private BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    // ---------------------------------------------------------------
    // PRIVATE: Stock reversal, mirrors cancelOrder() in OrderService
    //   Case 1: usage rows exist  -> proportional restore across batches
    //   Case 2: offline-imported, no batchId -> skip
    //   Case 3: single batchId fallback -> restore directly
    //   SERVICE -> skip (no physical stock)
    //
    // normalizedReturnQty is BASE units, the scale of usage.getQuantity() and
    // StockBatch.quantity. A display quantity here puts back a thousandth of the goods.
    // RECIPE pieces are their own scale: the usage rows hold ingredient base units and
    // apportion() works off the ratio, so a partial return still restores exactly its
    // share of each ingredient.
    // ---------------------------------------------------------------

    private int alreadyRestoredQtyForItem(Long orderItemId) {
        return orderItemStockUsageRepository.sumRestoredQtyByOrderItemId(orderItemId);
    }

    private List<UsageAllocation> apportion(int cumulativeReturn, List<OrderItemStockUsage> usages,
                                            int originalQty) {
        List<UsageAllocation> allocations = new ArrayList<>();
        int allocated = 0;
        for (OrderItemStockUsage usage : usages) {
            long numerator = (long) cumulativeReturn * usage.getQuantity();
            int target = (int) (numerator / originalQty);
            long remainder = numerator % originalQty;
            allocations.add(new UsageAllocation(usage, target, remainder));
            allocated += target;
        }

        int leftover = cumulativeReturn - allocated;
        // Multi-batch lines keep the invariant that the targets add up to the cumulative
        // return; for RECIPE lines the usage rows sum to ingredient base units, not
        // pieces, so leftover is negative and this loop does not apply.
        if (leftover > 0) {
            List<UsageAllocation> byLargestRemainder = new ArrayList<>(allocations);
            byLargestRemainder.sort(Comparator.comparingLong(UsageAllocation::remainder).reversed()
                    .thenComparingInt(a -> a.usage().getQuantity())
                    .thenComparing(a -> a.usage().getId()));
            for (UsageAllocation allocation : byLargestRemainder) {
                if (leftover == 0) break;
                if (allocation.target() < allocation.usage().getQuantity()) {
                    allocation.increaseTarget();
                    leftover--;
                }
            }
        }
        return allocations;
    }

    private static final class UsageAllocation {
        private final OrderItemStockUsage usage;
        private int target;
        private final long remainder;

        private UsageAllocation(OrderItemStockUsage usage, int target, long remainder) {
            this.usage = usage;
            this.target = target;
            this.remainder = remainder;
        }

        private OrderItemStockUsage usage() { return usage; }
        private int target() { return target; }
        private long remainder() { return remainder; }
        private void increaseTarget() { target++; }
    }

    private boolean reverseStockForReturnItem(OrderItem originalItem, int normalizedReturnQty,
                                               Long branchId, boolean offlineImported) {
        Item item = itemRepository.findById(originalItem.getItemId()).orElse(null);
        if (item != null && item.getItemType() == ItemType.SERVICE) {
            return false;
        }

        List<OrderItemStockUsage> usages =
                orderItemStockUsageRepository.findByOrderItemId(originalItem.getId());

        if (!usages.isEmpty()) {
            int originalQty = originalItem.getQty();
            if (originalQty <= 0) return false;

            int previouslyRestored = alreadyRestoredQtyForItem(originalItem.getId());
            int cumulativeReturn = (int) Math.min(
                    (long) previouslyRestored + normalizedReturnQty, originalQty);
            List<UsageAllocation> allocations = apportion(cumulativeReturn, usages, originalQty);
            Map<Long, StockBatch> batchesToSave = new LinkedHashMap<>();
            List<OrderItemStockUsage> usagesToSave = new ArrayList<>();

            for (UsageAllocation allocation : allocations) {
                OrderItemStockUsage usage = allocation.usage();
                int alreadyRestored = usage.getRestoredQuantity();
                int restoreQty = Math.max(0, Math.min(
                        allocation.target() - alreadyRestored,
                        usage.getQuantity() - alreadyRestored));
                if (restoreQty <= 0) continue;

                StockBatch batch = stockBatchRepository.findById(usage.getBatchId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Stock batch not found for return stock restoration"));
                batch.setQuantity(batch.getQuantity() + restoreQty);
                usage.setRestoredQuantity(alreadyRestored + restoreQty);
                batchesToSave.put(batch.getId(), batch);
                usagesToSave.add(usage);
            }
            if (!batchesToSave.isEmpty()) {
                stockBatchRepository.saveAll(batchesToSave.values());
                orderItemStockUsageRepository.saveAll(usagesToSave);
            }
            return true;

        } else if (offlineImported && originalItem.getBatchId() == null) {
            return false;

        } else if (originalItem.getBatchId() != null) {
            StockBatch batch = stockBatchRepository.findById(originalItem.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Original batch not found for return: " + originalItem.getItemName()));
            batch.setQuantity(batch.getQuantity() + normalizedReturnQty);
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

    // Carries validated data between validation and persistence.
    // returnQty is DISPLAY units (what is persisted, printed and shown);
    // normalizedReturnQty is BASE units (what every stock calculation uses).
    private record ValidatedReturnLine(
            OrderItem originalItem,
            BigDecimal returnQty,
            int normalizedReturnQty,
            double refundLineAmount) {
    }
}