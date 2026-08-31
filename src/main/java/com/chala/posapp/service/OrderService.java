package com.chala.posapp.service;

import com.chala.posapp.dto.customer.CustomerOrderListResponse;
import com.chala.posapp.dto.order.CancelOrderRequest;
import com.chala.posapp.dto.order.CreateOrderRequest;
import com.chala.posapp.dto.order.OfflineSaleImportRequest;
import com.chala.posapp.dto.order.OfflineSaleImportResponse;
import com.chala.posapp.dto.order.OrderItemRequest;
import com.chala.posapp.dto.order.OrderItemResponse;
import com.chala.posapp.dto.order.OrderPaymentRequest;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.dto.order.StockShortageIssue;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.exception.StockOverrideRequiredException;
import com.chala.posapp.repository.*;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.audit.Audited;
import com.chala.posapp.util.CacheKeyUtils;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemStockUsageRepository orderItemStockUsageRepository;
    private final ItemRepository itemRepository;
    private final SecurityUtils securityUtils;
    private final InvoiceService invoiceService;
    private final CustomerRepository customerRepository;
    private final CashShiftRepository cashShiftRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final BranchServiceItemRepository branchServiceItemRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;
    private final DiningTableRepository diningTableRepository;
    private final PendingOrderRepository pendingOrderRepository;
    private final PendingOrderItemRepository pendingOrderItemRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final CreditPaymentRepository creditPaymentRepository;
    private final WarrantyRepository warrantyRepository;
    private final AppConfigurationService appConfigurationService;
    private final PromotionService promotionService;
    private final StockOverrideAuditRepository stockOverrideAuditRepository;
    private final PlatformTransactionManager transactionManager;
    private final UserRepository userRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final ReportCacheInvalidator reportCacheInvalidator;

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

    // Cache eviction is NOT annotated here. It used to be, and importOfflineSale() calls
    // createOrderInternal() directly — so every sale synced back from an offline till
    // walked straight past it and invalidated nothing. reportCacheInvalidator is called
    // from createOrderInternal instead, which both paths go through.
    // MISS-03: Audit every sale creation
    @Audited(entity = "ORDER", action = "CREATE",
             summaryExpression = "'Branch ' + #request.branchId + ' | items=' + #request.items.size()")
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        return createOrderInternal(request, null);
    }

    @Transactional
    public OfflineSaleImportResponse importOfflineSale(OfflineSaleImportRequest request) {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER && user.getRole() != Role.CASHIER) {
            throw new BadRequestException("Not allowed");
        }
        if (request.getOrderType() != OrderType.CASH) {
            throw new BadRequestException("Offline import supports CASH sales only");
        }
        SaleMode saleMode = request.getSaleMode() == null ? SaleMode.TAKEAWAY : request.getSaleMode();
        if (saleMode != SaleMode.TAKEAWAY) {
            throw new BadRequestException("Offline import supports TAKEAWAY sales only");
        }

        Order existingOrder = orderRepository.findByClientSaleId(request.getClientSaleId()).orElse(null);
        if (existingOrder != null) {
            return OfflineSaleImportResponse.builder()
                    .clientSaleId(request.getClientSaleId())
                    .success(true)
                    .orderId(existingOrder.getId())
                    .invoiceNo(existingOrder.getInvoiceNo())
                    .message("Already imported")
                    .build();
        }

        CreateOrderRequest createOrderRequest = new CreateOrderRequest();
        createOrderRequest.setBranchId(request.getBranchId());
        createOrderRequest.setOrderType(request.getOrderType());
        createOrderRequest.setSaleMode(saleMode);
        createOrderRequest.setTableId(request.getTableId());
        createOrderRequest.setCustomerId(request.getCustomerId());
        createOrderRequest.setItems(request.getItems());
        createOrderRequest.setBillDiscount(request.getBillDiscount());
        createOrderRequest.setPaidAmount(request.getPaidAmount());
        createOrderRequest.setPaymentMethod(request.getPaymentMethod());
        createOrderRequest.setNote(request.getNote());

        OrderResponse response = createOrderInternal(
                createOrderRequest,
                new OfflineOrderMetadata(
                        request.getClientSaleId(),
                        request.getOfflineSoldAt(),
                        request.getInvoiceNo(),
                        request.getOfflineCashierUserId()
                )
        );

        return OfflineSaleImportResponse.builder()
                .clientSaleId(request.getClientSaleId())
                .success(true)
                .orderId(response.getId())
                .invoiceNo(response.getInvoiceNo())
                .message("Imported")
                .build();
    }

    /**
     * Import many queued sales, each standing or falling on its own.
     *
     * This used to be @Transactional and call this.importOfflineSale() directly. Spring's
     * transaction advice lives on the proxy, so a self-invocation never opened the inner
     * transaction the annotation promised and every row shared one — the per-row
     * success/failure this returns described an isolation the code did not have, and a
     * failed row's partial work stayed in the same persistence context until commit.
     *
     * Each row now runs in its own REQUIRES_NEW transaction through the transaction
     * manager, which does not depend on going through the proxy. A row that fails rolls
     * back only itself.
     */
    public List<OfflineSaleImportResponse> importOfflineSales(List<OfflineSaleImportRequest> requests) {
        TransactionTemplate perRowTransaction = new TransactionTemplate(transactionManager);
        perRowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<OfflineSaleImportResponse> results = new ArrayList<>();
        for (OfflineSaleImportRequest request : requests) {
            try {
                results.add(perRowTransaction.execute(status -> importOfflineSale(request)));
            } catch (Exception exception) {
                results.add(OfflineSaleImportResponse.builder()
                        .clientSaleId(request.getClientSaleId())
                        .success(false)
                        .message(exception.getMessage())
                        .build());
            }
        }
        return results;
    }

    private OrderResponse createOrderInternal(CreateOrderRequest request, OfflineOrderMetadata offlineOrderMetadata) {
        User user = securityUtils.getCurrentUser();

        Long branchId;
        if (user.getRole() == Role.CASHIER || user.getRole() == Role.MANAGER) {
            branchId = securityUtils.requireAssignedBranch(user);
            if (request.getBranchId() != null && !branchId.equals(request.getBranchId())) {
                throw new BadRequestException("Cannot create orders for another branch");
            }
        } else {
            if (request.getBranchId() == null) throw new BadRequestException("BranchId required");
            branchId = request.getBranchId();
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BadRequestException("Order items required");
        }

        // An imported sale belongs to the cashier who made it, not to whoever pressed
        // import. Attributing it to the importer also sent the cash to the wrong drawer,
        // because addCashSaleToOpenShift resolves the shift by this same id.
        Long cashierUserId = user.getId();
        if (offlineOrderMetadata != null && offlineOrderMetadata.cashierUserId != null) {
            User offlineCashier = userRepository.findById(offlineOrderMetadata.cashierUserId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Offline cashier not found: " + offlineOrderMetadata.cashierUserId));
            if (offlineCashier.getBranchId() != null && !branchId.equals(offlineCashier.getBranchId())) {
                throw new BadRequestException("Offline cashier belongs to another branch");
            }
            cashierUserId = offlineCashier.getId();
        }

        if (offlineOrderMetadata != null) {
            // The cash from this sale has to land in a real drawer. Silently dropping it
            // when nothing is open — which is what ifPresent did — is how offline revenue
            // went missing. The queue page gates on this too, against the same rule.
            final Long shiftCashierUserId = cashierUserId;
            cashShiftRepository
                    .findByBranchIdAndCashierUserIdAndStatus(branchId, shiftCashierUserId, ShiftStatus.OPEN)
                    .orElseThrow(() -> new BadRequestException(
                            "No open shift for the cashier who made this sale. Open a shift for them at this branch before importing."));
        }

        SaleMode saleMode = request.getSaleMode() == null ? SaleMode.TAKEAWAY : request.getSaleMode();
        DiningTable diningTable = resolveDiningTable(user, branchId, saleMode, request.getTableId());

        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        // An offline sale was already printed with a number the customer is holding.
        // Generating a fresh one here meant that receipt matched nothing in the system,
        // so a return could not be looked up from it.
        String invoiceNo = offlineOrderMetadata != null
                && offlineOrderMetadata.invoiceNo != null
                && !offlineOrderMetadata.invoiceNo.isBlank()
                ? offlineOrderMetadata.invoiceNo.trim()
                : invoiceService.generateInvoiceNo(branchId);

        double subTotal = 0;
        double promotionDiscountTotal = 0;
        // The moment the sale actually happened: the queued timestamp for an offline
        // import, now for a live checkout. Promotions were already evaluated against
        // this, and created_at now uses it too — otherwise a sale made during an outage
        // is booked on the day it was pushed rather than the day it was made.
        // imported_at remains the record of when it reached the server.
        LocalDateTime soldAt = offlineOrderMetadata != null && offlineOrderMetadata.offlineSoldAt != null
                ? offlineOrderMetadata.offlineSoldAt
                : LocalDateTime.now();
        // Promotions are deliberately NOT applied to an imported offline sale.
        //
        // The till was offline when it priced this. It could not know which promotions
        // were running, so it charged and PRINTED list price, and that is what the
        // customer handed over. Applying a discount here would make the ledger disagree
        // with the receipt in their hand and book a discount nobody received — and
        // min(paidAmount, grandTotal) below would quietly absorb the difference as change
        // that was never given, showing up only as an unexplained cash surplus at day end.
        //
        // The alternative — caching promotions and pricing them in the browser — means a
        // second implementation of scopes, targets, caps, priority and best-of selection.
        // Any drift between the two reintroduces exactly this mismatch, so the sale is
        // banked at the price it was actually sold for instead.
        List<Promotion> activePromotions = offlineOrderMetadata != null
                ? List.of()
                : promotionService.activePromotionsForBranch(branchId, soldAt);
        List<PreparedOrderItem> preparedItems = new ArrayList<>();
        Map<Long, StockBatch> batchesToUpdate = new LinkedHashMap<>();
        StockOverrideContext stockOverrideContext =
                buildStockOverrideContext(request, user, offlineOrderMetadata != null);

        for (OrderItemRequest itemReq : request.getItems()) {
            validateWarrantySelection(itemReq, user.getRole(), branchId);
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemReq.getItemId()));

            if (!item.isActive()) {
                throw new BadRequestException("Item is inactive: " + item.getBarcode());
            }
            if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), branchId)) {
                throw new BadRequestException(item.getItemType().name() + " items are disabled in app configuration");
            }

            int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, itemReq.getQty(), itemReq.getQtyUnit());
            StockConsumptionResult consumption = consumeStockForSale(
                    branchId,
                    item,
                    itemReq,
                    normalizedQty,
                    batchesToUpdate,
                    stockOverrideContext
            );

            double unitPrice = itemReq.getUnitPrice();
            DiscountType discountType = itemReq.getDiscountType() == null ? DiscountType.NONE : itemReq.getDiscountType();
            double discountValue = itemReq.getDiscountValue();
            PromotionApplication promotionApplication = promotionService.calculateBestDiscount(
                    item,
                    branchId,
                    unitPrice,
                    normalizedQty,
                    discountType,
                    discountValue,
                    activePromotions
            );
            discountType = promotionApplication.discountType();
            discountValue = promotionApplication.discountValue();
            double finalUnitPrice = calculateFinalUnitPrice(unitPrice, discountType, discountValue);
            double lineTotal = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(finalUnitPrice), normalizedQty).doubleValue();
            if (promotionApplication.promotionApplied()) {
                promotionDiscountTotal += promotionApplication.promotionDiscountAmount();
            }

            OrderItem orderItem = OrderItem.builder()
                    .itemId(item.getId())
                    .batchId(consumption.primaryBatchId)
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .altName(item.getAltName())
                    .itemType(item.getItemType())
                    .qty(normalizedQty)
                    .displayQty(itemReq.getQty().stripTrailingZeros())
                    .qtyUnit(resolveQtyUnit(item, itemReq))
                    .unitPrice(unitPrice)
                    .costPrice(consumption.unitCost)
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .promotionId(promotionApplication.promotionApplied() ? promotionApplication.promotionId() : null)
                    .promotionName(promotionApplication.promotionApplied() ? promotionApplication.promotionName() : null)
                    .promotionDiscountAmount(promotionApplication.promotionApplied() ? promotionApplication.promotionDiscountAmount() : 0.0)
                    .finalUnitPrice(finalUnitPrice)
                    .lineCost(consumption.lineCost)
                    .lineTotal(lineTotal)
                    .warrantyLabel(itemReq.getWarrantyLabel())
                    .warrantyPeriodValue(itemReq.getWarrantyPeriodValue())
                    .warrantyPeriodUnit(itemReq.getWarrantyPeriodUnit())
                    .build();

            preparedItems.add(new PreparedOrderItem(orderItem, consumption.usages, consumption.overrides));
            subTotal += lineTotal;
        }

        double billDiscount = request.getBillDiscount();
        if (billDiscount < 0) billDiscount = 0;
        if (billDiscount > subTotal) billDiscount = subTotal;
        PromotionOrderApplication billPromotionApplication = promotionService.calculateBestOrderDiscount(
                branchId,
                request.getCustomerId(),
                subTotal,
                billDiscount,
                activePromotions
        );
        billDiscount = billPromotionApplication.appliedDiscountAmount();
        if (billPromotionApplication.promotionApplied()) {
            promotionDiscountTotal += billPromotionApplication.promotionDiscountAmount();
        }

        double grandTotal = roundRupee(subTotal - billDiscount);
        double paidAmount = request.getPaidAmount();
        if (paidAmount < 0) paidAmount = 0;
        paidAmount = roundRupee(paidAmount);

        double dueAmount;
        Customer customer = null;
        OrderType effectiveOrderType = request.getOrderType();

        double paidTowardSale = Math.min(paidAmount, grandTotal);
        dueAmount = roundMoney(grandTotal - paidTowardSale);
        String paymentMethod = normalizeSalePaymentMethod(request.getPaymentMethod(), paidTowardSale, dueAmount);
        if (dueAmount > 0) {
            if (isFreePlanWithoutCustomerCredit()) {
                throw new BadRequestException("Credit sales are not available in FREE plan");
            }
            if (request.getCustomerId() == null) {
                throw new BadRequestException("Customer required when paid amount is less than grand total");
            }
            effectiveOrderType = OrderType.CREDIT;
            customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
            if (!customer.isActive()) {
                throw new BadRequestException("Cannot create credit sale for inactive customer");
            }
            if (customer.getCreditLimit() != null) {
                double projectedDueAmount = customer.getDueAmount() + dueAmount;
                if (projectedDueAmount > customer.getCreditLimit()) {
                    throw new BadRequestException(String.format(
                            "Customer credit limit exceeded. Current due: %.2f, sale amount: %.2f, limit: %.2f",
                            customer.getDueAmount(),
                            dueAmount,
                            customer.getCreditLimit()
                    ));
                }
            }
        } else {
            dueAmount = 0;
            if (effectiveOrderType == OrderType.CREDIT && request.getCustomerId() != null) {
                customerRepository.findById(request.getCustomerId())
                        .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
            }
            effectiveOrderType = OrderType.CASH;
        }

        Order order = Order.builder()
                .invoiceNo(invoiceNo)
                .clientSaleId(offlineOrderMetadata != null ? offlineOrderMetadata.clientSaleId : null)
                .branchId(branchId)
                .receiptBranchName(branch.getName())
                .receiptBranchAddress(branch.getAddress())
                .receiptBranchPhone(branch.getPhone())
                .receiptBranchLogo(branch.getLogo())
                .cashierUserId(cashierUserId)
                .customerId(request.getCustomerId())
                .orderType(effectiveOrderType)
                .paymentMethod(paymentMethod)
                .saleMode(saleMode)
                .tableId(diningTable != null ? diningTable.getId() : null)
                .tableName(diningTable != null ? diningTable.getTableName() : null)
                .status(OrderStatus.COMPLETED)
                .subTotal(subTotal)
                .billDiscount(billDiscount)
                .promotionDiscountTotal(promotionDiscountTotal)
                .billPromotionId(billPromotionApplication.promotionApplied() ? billPromotionApplication.promotionId() : null)
                .billPromotionName(billPromotionApplication.promotionApplied() ? billPromotionApplication.promotionName() : null)
                .billPromotionDiscountAmount(billPromotionApplication.promotionApplied() ? billPromotionApplication.promotionDiscountAmount() : 0.0)
                .grandTotal(grandTotal)
                .paidAmount(paidAmount)
                .dueAmount(dueAmount)
                .salePaidAmount(paidTowardSale)
                .saleDueAmount(dueAmount)
                .note(request.getNote())
                // businessDate is derived from this in Order's @PrePersist, so every
                // path that builds an Order gets the same rule.
                .createdAt(soldAt)
                .offlineSoldAt(offlineOrderMetadata != null ? offlineOrderMetadata.offlineSoldAt : null)
                .importedAt(offlineOrderMetadata != null ? LocalDateTime.now() : null)
                .offlineImported(offlineOrderMetadata != null)
                .build();

        Order savedOrder = orderRepository.save(order);

        List<OrderItem> orderItemsToSave = preparedItems.stream()
                .map(PreparedOrderItem::orderItem)
                .toList();
        orderItemsToSave.forEach(orderItem -> orderItem.setOrderId(savedOrder.getId()));
        List<OrderItem> savedOrderItems = orderItemRepository.saveAll(orderItemsToSave);

        List<OrderItemStockUsage> usagesToSave = new ArrayList<>();
        for (int i = 0; i < savedOrderItems.size(); i++) {
            OrderItem savedOrderItem = savedOrderItems.get(i);
            for (StockUsageDraft usage : preparedItems.get(i).usages) {
                usagesToSave.add(OrderItemStockUsage.builder()
                        .orderItemId(savedOrderItem.getId())
                        .itemId(usage.itemId)
                        .batchId(usage.batchId)
                        .quantity(usage.quantity)
                        .build());
            }
        }
        if (!usagesToSave.isEmpty()) {
            orderItemStockUsageRepository.saveAll(usagesToSave);
        }

        List<StockOverrideAudit> overrideAuditsToSave = new ArrayList<>();
        for (int i = 0; i < savedOrderItems.size(); i++) {
            OrderItem savedOrderItem = savedOrderItems.get(i);
            for (StockOverrideDraft override : preparedItems.get(i).overrides) {
                overrideAuditsToSave.add(StockOverrideAudit.builder()
                        .orderId(savedOrder.getId())
                        .orderItemId(savedOrderItem.getId())
                        .branchId(branchId)
                        .saleItemId(override.saleItemId)
                        .saleItemName(override.saleItemName)
                        .stockItemId(override.stockItemId)
                        .stockItemName(override.stockItemName)
                        .batchId(override.batchId)
                        .requiredQuantity(override.requiredQuantity)
                        .availableQuantity(override.availableQuantity)
                        .shortageQuantity(override.shortageQuantity)
                        .qtyUnit(override.qtyUnit)
                        .overrideMode(stockOverrideContext.mode)
                        .overrideUserId(user.getId())
                        .overrideUsername(user.getUsername())
                        .reason(stockOverrideContext.reason)
                        .build());
            }
        }
        if (!overrideAuditsToSave.isEmpty()) {
            stockOverrideAuditRepository.saveAll(overrideAuditsToSave);
        }

        List<Warranty> warrantiesToSave = buildWarranties(savedOrder, savedOrderItems, customer);
        if (!warrantiesToSave.isEmpty()) {
            warrantyRepository.saveAll(warrantiesToSave);
        }

        if (!batchesToUpdate.isEmpty()) {
            stockBatchRepository.saveAll(batchesToUpdate.values());
        }

        if (customer != null) {
            customer.setDueAmount(customer.getDueAmount() + savedOrder.getDueAmount());
            customerRepository.save(customer);
        }

        addCashSaleToOpenShift(savedOrder);
        if (saleMode == SaleMode.DINE_IN && diningTable != null) {
            finalizeDineInSession(diningTable);
        }

        // Both the online checkout and the offline sync land here, which is why the
        // invalidation lives at this level rather than on the public entry points.
        reportCacheInvalidator.salesChanged(branchId);

        return buildOrderResponse(savedOrder, savedOrderItems);
    }

    @Audited(entity = "ORDER", action = "CANCEL",
             summaryExpression = "'Invoice=' + #invoiceNo")
    @Transactional
    public OrderResponse cancelOrder(String invoiceNo, CancelOrderRequest request) {
        User user = securityUtils.getCurrentUser();

        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() == OrderStatus.CANCELED) {
            throw new AlreadyExistsException("Order already canceled");
        }

        ensureBranchAccess(user, order.getBranchId());

        if (order.getOrderType() == OrderType.CREDIT && order.getCustomerId() != null) {
            Customer customer = customerRepository.findById(order.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

            double newDue = customer.getDueAmount() - order.getDueAmount();
            customer.setDueAmount(Math.max(0, newDue));
            customerRepository.save(customer);
        }

        order.setStatus(OrderStatus.CANCELED);
        order.setCancelReason(request.getReason());
        order.setCanceledAt(LocalDateTime.now());

        List<Warranty> warranties = warrantyRepository.findByOrderId(order.getId());
        if (!warranties.isEmpty()) {
            warranties.forEach(warranty -> warranty.setStatus(WarrantyStatus.VOID));
            warrantyRepository.saveAll(warranties);
        }
        // FIX: removed duplicate setCanceledAt() call that was here
        Order saved = orderRepository.save(order);
        reverseCashSaleFromOpenShift(saved);

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        for (OrderItem orderItem : items) {
            List<OrderItemStockUsage> usages = orderItemStockUsageRepository.findByOrderItemId(orderItem.getId());
            if (!usages.isEmpty()) {
                restoreStockUsages(usages, order.getBranchId());
            } else if (order.isOfflineImported() && orderItem.getBatchId() == null) {
                // Legacy imported sales do not create stock usage rows or batch references.
                continue;
            } else {
                restoreStockToOriginalBatch(orderItem, order.getBranchId());
            }
        }

        // A cancelled sale used to stay in every report until its TTL expired — nothing
        // on this path evicted anything.
        reportCacheInvalidator.salesChanged(order.getBranchId());

        return buildOrderResponse(saved, items);
    }

    public OrderResponse getOrder(String invoiceNo) {
        User user = securityUtils.getCurrentUser();
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        ensureBranchAccess(user, order.getBranchId());
        return mapToOrderResponse(order, true);
    }

    @Transactional
    public OrderResponse recordPayment(String invoiceNo, OrderPaymentRequest request) {
        User user = securityUtils.getCurrentUser();
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        ensureBranchAccess(user, order.getBranchId());

        if (order.getStatus() == OrderStatus.CANCELED) {
            throw new BadRequestException("Cannot record payment for a canceled order");
        }
        if (order.getCustomerId() == null) {
            throw new BadRequestException("Order is not linked to a customer");
        }

        double orderDue = roundMoney(order.getDueAmount());
        if (orderDue <= 0) {
            throw new BadRequestException("This order has no due amount");
        }

        double paymentAmount = roundMoney(request.getAmount());
        if (paymentAmount <= 0) {
            throw new BadRequestException("Payment amount must be greater than 0");
        }
        if (paymentAmount > orderDue) {
            throw new BadRequestException("Payment amount exceeds this order due amount: " + orderDue);
        }

        Customer customer = customerRepository.findById(order.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        double customerDue = roundMoney(customer.getDueAmount());
        if (paymentAmount > customerDue) {
            throw new BadRequestException("Payment amount exceeds the customer due amount: " + customerDue);
        }

        order.setPaidAmount(roundMoney(order.getPaidAmount() + paymentAmount));
        order.setDueAmount(roundMoney(orderDue - paymentAmount));

        customer.setDueAmount(roundMoney(customerDue - paymentAmount));

        Order savedOrder = orderRepository.save(order);
        customerRepository.save(customer);

        String method = normalizePaymentMethod(request.getPaymentMethod(), "CASH");
        String note = request.getNote() == null || request.getNote().isBlank()
                ? "Payment for invoice " + order.getInvoiceNo() + " (" + method + ")"
                : request.getNote().trim() + " (" + method + ", invoice " + order.getInvoiceNo() + ")";

        creditPaymentRepository.save(CreditPayment.builder()
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .cashierUserId(user.getId())
                .amount(paymentAmount)
                .paymentMethod(method)
                .note(note)
                .build());

        // Settling a due changes credit aging and the dashboard's outstanding figure.
        reportCacheInvalidator.salesChanged(savedOrder.getBranchId());

        return mapToOrderResponse(savedOrder, true);
    }

    public Page<OrderResponse> getAllOrders(
            String search,
            int page,
            int size,
            String branchIdString,
            String from,
            String to,
            String orderType,
            String customerType,
            Long cashierId,
            String totalOperator,
            Double totalAmount
    ) {
        User user = securityUtils.getCurrentUser();

        if (!securityUtils.isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Access denied: Only Admin or Manager can perform this action.");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Long branchId = 0L;
        if (branchIdString != null && !branchIdString.isEmpty()) {
            try {
                branchId = Long.parseLong(branchIdString);
            } catch (NumberFormatException ignored) {
                branchId = 0L;
            }
        }

        if (branchId != 0L) {
            branchRepository.findById(branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        }

        if (!securityUtils.isAdminLike(user)) {
            Long userBranchId = securityUtils.requireAssignedBranch(user);
            if (branchId == 0L) {
                branchId = userBranchId;
            } else if (!userBranchId.equals(branchId)) {
                throw new BadRequestException("Cannot view another branch");
            }
        }

        LocalDateTime fromDateTime = parseStartOfDay(from);
        LocalDateTime toDateTime = parseEndOfDay(to);
        OrderType normalizedOrderType = parseOrderType(orderType);
        String normalizedCustomerType = normalizeFilter(customerType);
        Long normalizedCashierId = cashierId != null && cashierId > 0 ? cashierId : null;
        String normalizedTotalOperator = normalizeFilter(totalOperator);
        Double normalizedTotalAmount = totalAmount != null && totalAmount >= 0 ? totalAmount : null;

        Page<Order> orderPage = orderRepository.searchOrders(
                search,
                branchId != 0L ? branchId : null,
                fromDateTime,
                toDateTime,
                normalizedOrderType,
                normalizedCustomerType,
                normalizedCashierId,
                normalizedTotalOperator,
                normalizedTotalAmount,
                pageable
        );

        return orderPage.map(order -> mapToOrderResponse(order, false));
    }

    private LocalDateTime parseStartOfDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value).atStartOfDay();
    }

    private LocalDateTime parseEndOfDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value).plusDays(1).atStartOfDay().minusNanos(1);
    }

    private OrderType parseOrderType(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("ALL")) {
            return null;
        }
        return OrderType.valueOf(value.trim().toUpperCase());
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return "ALL";
        }
        return value.trim().toUpperCase();
    }

    public Page<CustomerOrderListResponse> list(Long customerId, String orderType, Pageable pageable) {
        customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Page<Order> page;
        if (orderType == null || orderType.equalsIgnoreCase("ALL")) {
            page = orderRepository.findByCustomerId(customerId, pageable);
        } else {
            OrderType type = OrderType.valueOf(orderType.toUpperCase());
            page = orderRepository.findByCustomerIdAndOrderType(customerId, type, pageable);
        }
        return page.map(this::map);
    }

    private DiningTable resolveDiningTable(User user, Long branchId, SaleMode saleMode, Long tableId) {
        if (saleMode == SaleMode.DINE_IN) {
            if (!appConfigurationService.isDineInEnabled(branchId)) {
                throw new BadRequestException("Dine-in is disabled in app configuration");
            }

            if (tableId == null) {
                throw new BadRequestException("Table ID is required for dine-in orders");
            }

            DiningTable table = diningTableRepository.findById(tableId)
                    .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
            ensureBranchAccess(user, branchId);
            if (!table.getBranchId().equals(branchId)) {
                throw new BadRequestException("Dining table does not belong to this branch");
            }
            return table;
        }

        if (tableId != null) {
            throw new BadRequestException("Table ID is only allowed for dine-in orders");
        }
        return null;
    }

    private StockConsumptionResult consumeStockForSale(
            Long branchId,
            Item item,
            OrderItemRequest itemReq,
            int normalizedQty,
            Map<Long, StockBatch> batchesToUpdate,
            StockOverrideContext stockOverrideContext
    ) {
        if (item.getItemType() == ItemType.SERVICE) {
            if (!branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
                throw new BadRequestException("Service item is not assigned to this branch: " + item.getName());
            }
            double unitCost = item.getCostPrice() != null ? item.getCostPrice().doubleValue() : 0.0;
            double lineCost = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(unitCost), normalizedQty).doubleValue();
            return new StockConsumptionResult(null, unitCost, lineCost, List.of(), List.of());
        }

        if (item.getItemType() == ItemType.RECIPE) {
            List<RecipeIngredient> recipeIngredients = recipeIngredientRepository.findByParentItemId(item.getId());

            Map<Long, Item> ingredientMap = itemRepository.findAllById(
                            recipeIngredients.stream()
                                    .map(RecipeIngredient::getIngredientId)
                                    .distinct()
                                    .toList()
                    ).stream()
                    .collect(Collectors.toMap(Item::getId, ingredient -> ingredient));

            List<StockUsageDraft> usages = new ArrayList<>();
            List<StockOverrideDraft> overrides = new ArrayList<>();
            double lineCost = 0.0;

            for (RecipeIngredient recipeIngredient : recipeIngredients) {
                Item ingredientItem = ingredientMap.get(recipeIngredient.getIngredientId());
                if (ingredientItem == null) {
                    throw new ResourceNotFoundException("Ingredient item not found: " + recipeIngredient.getIngredientId());
                }

                long requiredQty = (long) recipeIngredient.getQuantity() * normalizedQty;
                if (requiredQty > Integer.MAX_VALUE) {
                    throw new BadRequestException("Recipe quantity is too large for item: " + item.getName());
                }

                InventoryConsumptionResult ingredientConsumption = consumeInventoryItem(
                        branchId,
                        item,
                        ingredientItem,
                        null,
                        null,
                        (int) requiredQty,
                        batchesToUpdate,
                        recipeIngredientStockContext()
                );
                usages.addAll(ingredientConsumption.usages);
                lineCost += ingredientConsumption.lineCost;
                overrides.addAll(ingredientConsumption.overrides);
            }

            lineCost += calculateRecipeOverheadCost(item, normalizedQty, lineCost);

            double unitCost = normalizedQty == 0
                    ? 0.0
                    : BigDecimal.valueOf(lineCost)
                    .divide(BigDecimal.valueOf(normalizedQty), 6, RoundingMode.HALF_UP)
                    .doubleValue();

            return new StockConsumptionResult(null, unitCost, lineCost, usages, overrides);
        }

        if (isFreePlanWithoutStockManagement()) {
            double unitCost = item.getCostPrice() != null ? item.getCostPrice().doubleValue() : 0.0;
            double lineCost = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(unitCost), normalizedQty).doubleValue();
            return new StockConsumptionResult(itemReq.getBatchId(), unitCost, lineCost, List.of(), List.of());
        }

        InventoryConsumptionResult inventoryConsumption = consumeInventoryItem(
                branchId,
                item,
                item,
                itemReq.getBatchId(),
                BigDecimal.valueOf(itemReq.getUnitPrice()),
                normalizedQty,
                batchesToUpdate,
                stockOverrideContext
        );

        return new StockConsumptionResult(
                inventoryConsumption.primaryBatchId,
                inventoryConsumption.unitCost,
                inventoryConsumption.lineCost,
                inventoryConsumption.usages,
                inventoryConsumption.overrides
        );
    }

    private InventoryConsumptionResult consumeInventoryItem(
            Long branchId,
            Item saleItem,
            Item item,
            Long explicitBatchId,
            BigDecimal preferredSellingPrice,
            int normalizedQty,
            Map<Long, StockBatch> batchesToUpdate,
            StockOverrideContext stockOverrideContext
    ) {
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            throw new BadRequestException("Stock-tracked grocery item required for stock deduction");
        }

        if (explicitBatchId != null) {
            StockBatch batch = stockBatchRepository.findById(explicitBatchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Batch not found ID: " + explicitBatchId));

            if (!batch.getItem().getId().equals(item.getId())) {
                throw new BadRequestException("Batch ID does not match Item ID");
            }
            if (!batch.getBranch().getId().equals(branchId)) {
                throw new BadRequestException("Batch does not belong to this branch");
            }

            int availableQty = positiveQuantity(batch);
            if (availableQty < normalizedQty && !stockOverrideContext.allowed) {
                throwStockShortage(saleItem, item, batch, normalizedQty, availableQty, stockOverrideContext);
            }
            decrementBatch(batch, normalizedQty, stockOverrideContext.allowed);
            batchesToUpdate.put(batch.getId(), batch);

            double lineCost = QuantityConversionUtil.calculateActualAmount(item, batch.getCostPrice(), normalizedQty).doubleValue();
            List<StockOverrideDraft> overrides = availableQty < normalizedQty
                    ? List.of(buildOverrideDraft(saleItem, item, batch.getId(), normalizedQty, availableQty, stockOverrideContext))
                    : List.of();
            return new InventoryConsumptionResult(
                    batch.getId(),
                    batch.getCostPrice().doubleValue(),
                    lineCost,
                    List.of(new StockUsageDraft(item.getId(), batch.getId(), normalizedQty)),
                    overrides
            );
        }

        List<StockBatch> availableBatches = preferredSellingPrice == null
                ? stockBatchRepository.findAvailableBatches(branchId, item.getId())
                : stockBatchRepository.findAvailableBatchesBySellingPrice(branchId, item.getId(), preferredSellingPrice);
        if (availableBatches.isEmpty()) {
            availableBatches = stockBatchRepository.findAvailableBatches(branchId, item.getId());
        }
        int remainingQty = normalizedQty;
        List<StockUsageDraft> usages = new ArrayList<>();
        List<StockOverrideDraft> overrides = new ArrayList<>();
        double lineCost = 0.0;
        int availableBefore = availableBatches.stream()
                .mapToInt(this::positiveQuantity)
                .sum();
        StockBatch lastUsedBatch = null;

        for (StockBatch batch : availableBatches) {
            if (remainingQty <= 0) {
                break;
            }

            int availableQty = batch.getQuantity() == null ? 0 : batch.getQuantity();
            if (availableQty <= 0) {
                continue;
            }

            int usedQty = Math.min(availableQty, remainingQty);
            decrementBatch(batch, usedQty, false);
            batchesToUpdate.put(batch.getId(), batch);
            lastUsedBatch = batch;

            usages.add(new StockUsageDraft(item.getId(), batch.getId(), usedQty));
            lineCost += QuantityConversionUtil.calculateActualAmount(item, batch.getCostPrice(), usedQty).doubleValue();
            remainingQty -= usedQty;
        }

        if (remainingQty > 0) {
            if (!stockOverrideContext.allowed) {
                throwStockShortage(saleItem, item, lastUsedBatch, normalizedQty, availableBefore, stockOverrideContext);
            }

            StockBatch overrideBatch = resolveOverrideBatch(branchId, item, lastUsedBatch);
            decrementBatch(overrideBatch, remainingQty, true);
            batchesToUpdate.put(overrideBatch.getId(), overrideBatch);

            usages.add(new StockUsageDraft(item.getId(), overrideBatch.getId(), remainingQty));
            lineCost += QuantityConversionUtil.calculateActualAmount(item, overrideBatch.getCostPrice(), remainingQty).doubleValue();
            overrides.add(buildOverrideDraft(saleItem, item, overrideBatch.getId(), normalizedQty, availableBefore, stockOverrideContext));
        }

        double unitCost = normalizedQty == 0
                ? 0.0
                : BigDecimal.valueOf(lineCost)
                .divide(BigDecimal.valueOf(normalizedQty), 6, RoundingMode.HALF_UP)
                .doubleValue();

        return new InventoryConsumptionResult(
                usages.isEmpty() ? null : usages.get(0).batchId,
                unitCost,
                lineCost,
                usages,
                overrides
        );
    }

    private StockOverrideContext buildStockOverrideContext(CreateOrderRequest request, User user, boolean offlineImport) {
        // An offline sale has already happened: the goods left the shelf and the cash is
        // in the drawer. Refusing it here un-sells nothing — it only keeps real revenue
        // off the books and leaves a paid transaction stranded in the queue forever. So
        // the shortfall is absorbed, stock is allowed to go negative, and the resulting
        // StockOverrideAudit rows become the reconciliation trail for what went short.
        if (offlineImport) {
            String reason = request.getStockOverrideReason() == null || request.getStockOverrideReason().isBlank()
                    ? "Offline sale imported after the fact"
                    : request.getStockOverrideReason();
            return new StockOverrideContext(
                    StockOverrideMode.ALWAYS_ALLOW,
                    true,
                    true,
                    normalizeOverrideReason(reason)
            );
        }

        StockOverrideMode mode = appConfigurationService.getStockOverrideMode(request.getBranchId());
        boolean requested = request.isAllowStockOverride();
        boolean roleAllowed = appConfigurationService.isStockOverrideAllowedForRole(user.getRole(), request.getBranchId());

        if (mode == StockOverrideMode.ALWAYS_ALLOW || mode == StockOverrideMode.MANAGER_OVERRIDE) {
            if (requested && !roleAllowed) {
                throw new BadRequestException("Stock override permission denied for this role");
            }
            return new StockOverrideContext(mode, requested && roleAllowed, roleAllowed, normalizeOverrideReason(request.getStockOverrideReason()));
        }
        return new StockOverrideContext(StockOverrideMode.BLOCK, false, false, normalizeOverrideReason(request.getStockOverrideReason()));
    }

    private StockOverrideContext recipeIngredientStockContext() {
        return new StockOverrideContext(
                StockOverrideMode.ALWAYS_ALLOW,
                true,
                true,
                "Recipe ingredient negative stock allowed"
        );
    }

    private String normalizeOverrideReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    private int positiveQuantity(StockBatch batch) {
        if (batch == null || batch.getQuantity() == null) {
            return 0;
        }
        return Math.max(0, batch.getQuantity());
    }

    private StockBatch resolveOverrideBatch(Long branchId, Item item, StockBatch lastUsedBatch) {
        if (lastUsedBatch != null) {
            return lastUsedBatch;
        }

        List<StockBatch> allBatches = stockBatchRepository.findBatchesForBranchItem(branchId, item.getId());
        if (!allBatches.isEmpty()) {
            return allBatches.get(0);
        }

        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        return stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(0)
                .originalQuantity(0)
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .sourceType(StockBatchSourceType.OVERRIDE)
                .batchCode("OVERRIDE-" + branchId + "-" + item.getId() + "-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
    }

    private void throwStockShortage(
            Item saleItem,
            Item stockItem,
            StockBatch batch,
            int requiredQty,
            int availableQty,
            StockOverrideContext stockOverrideContext
    ) {
        StockShortageIssue shortage = buildShortageIssue(saleItem, stockItem, batch, requiredQty, availableQty);
        String message = stockOverrideContext.overrideAvailable
                ? "Insufficient stock. Manager override required."
                : "Insufficient stock for " + stockItem.getName();
        throw new StockOverrideRequiredException(message, List.of(shortage), stockOverrideContext.overrideAvailable);
    }

    private StockShortageIssue buildShortageIssue(Item saleItem, Item stockItem, StockBatch batch, int requiredQty, int availableQty) {
        int safeAvailable = Math.max(0, availableQty);
        return StockShortageIssue.builder()
                .itemId(saleItem.getId())
                .itemName(saleItem.getName())
                .stockItemId(stockItem.getId())
                .stockItemName(stockItem.getName())
                .batchId(batch != null ? batch.getId() : null)
                .requiredQuantity(requiredQty)
                .availableQuantity(safeAvailable)
                .shortageQuantity(Math.max(0, requiredQty - safeAvailable))
                .unit(QuantityConversionUtil.primaryDisplayUnit(stockItem))
                .build();
    }

    private StockOverrideDraft buildOverrideDraft(
            Item saleItem,
            Item stockItem,
            Long batchId,
            int requiredQty,
            int availableQty,
            StockOverrideContext stockOverrideContext
    ) {
        int safeAvailable = Math.max(0, availableQty);
        return new StockOverrideDraft(
                saleItem.getId(),
                saleItem.getName(),
                stockItem.getId(),
                stockItem.getName(),
                batchId,
                requiredQty,
                safeAvailable,
                Math.max(0, requiredQty - safeAvailable),
                QuantityConversionUtil.primaryDisplayUnit(stockItem),
                stockOverrideContext.reason
        );
    }

    private boolean isFreePlanWithoutStockManagement() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return false;
        }
        // tenant_subscriptions lives in pos_master, not the tenant DB —
        // switch context before the transaction opens the Hibernate session.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        Boolean result = TenantContext.callWith("MASTER",
                () -> tx.execute(status ->
                        tenantSubscriptionRepository.findByTenantId(tenantId)
                                .map(TenantSubscription::getPlan)
                                .map(plan -> plan.getName() == null ? "" : plan.getName().trim().toUpperCase())
                                .map(planName -> planName.equals("FREE") || planName.equals("MONTHLY_DEMO"))
                                .orElse(false)
                ));
        return Boolean.TRUE.equals(result);
    }

    private boolean isFreePlanWithoutCustomerCredit() {
        return isFreePlanWithoutStockManagement();
    }

    private String normalizeSalePaymentMethod(String paymentMethod, double paidAmount, double dueAmount) {
        if (paidAmount <= 0 && dueAmount > 0) {
            return "CREDIT";
        }
        return normalizePaymentMethod(paymentMethod, "CASH");
    }

    private String normalizePaymentMethod(String paymentMethod, String fallback) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return fallback;
        }
        return paymentMethod.trim().toUpperCase();
    }

    private double calculateRecipeOverheadCost(Item item, int normalizedQty, double directLineCost) {
        if (item.getItemType() != ItemType.RECIPE || normalizedQty <= 0) {
            return 0.0;
        }

        ItemOverheadCostMode mode = item.getOverheadCostMode() == null ? ItemOverheadCostMode.NONE : item.getOverheadCostMode();
        BigDecimal value = item.getOverheadCostValue() == null ? BigDecimal.ZERO : item.getOverheadCostValue();
        if (mode == ItemOverheadCostMode.NONE || value.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }

        if (mode == ItemOverheadCostMode.FIXED) {
            return value.multiply(BigDecimal.valueOf(normalizedQty)).doubleValue();
        }

        return BigDecimal.valueOf(directLineCost)
                .multiply(value)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private void decrementBatch(StockBatch batch, int normalizedQty, boolean allowNegative) {
        int currentQuantity = batch.getQuantity() == null ? 0 : batch.getQuantity();
        if (!allowNegative && currentQuantity < normalizedQty) {
            throw new BadRequestException("Insufficient stock for " + batch.getItem().getName()
                    + " (Batch #" + batch.getId() + "). Available: " + currentQuantity);
        }
        batch.setQuantity(currentQuantity - normalizedQty);
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

    private MeasurementUnit resolveQtyUnit(Item item, OrderItemRequest itemReq) {
        if (QuantityConversionUtil.isMeasuredItem(item.getItemType())) {
            return itemReq.getQtyUnit() == null ? item.getDefaultUnit() : itemReq.getQtyUnit();
        }
        return item.getDefaultUnit();
    }

    private void restoreStockUsages(List<OrderItemStockUsage> usages, Long branchId) {
        Map<Long, StockBatch> batchesToSave = new LinkedHashMap<>();

        for (OrderItemStockUsage usage : usages) {
            StockBatch batch = stockBatchRepository.findById(usage.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Original batch not found for stock restoration"));

            if (!batch.getBranch().getId().equals(branchId)) {
                throw new BadRequestException("Original batch belongs to a different branch");
            }
            if (!batch.getItem().getId().equals(usage.getItemId())) {
                throw new BadRequestException("Original batch does not match the stock usage item");
            }

            batch.setQuantity(batch.getQuantity() + usage.getQuantity());
            batchesToSave.put(batch.getId(), batch);
        }

        if (!batchesToSave.isEmpty()) {
            stockBatchRepository.saveAll(batchesToSave.values());
        }
    }

    private void restoreStockToOriginalBatch(OrderItem orderItem, Long branchId) {
        Item item = itemRepository.findById(orderItem.getItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            return;
        }

        if (orderItem.getBatchId() == null) {
            throw new BadRequestException("Original batch reference is missing for item: " + orderItem.getItemName());
        }

        StockBatch batch = stockBatchRepository.findById(orderItem.getBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Original batch not found for item: " + orderItem.getItemName()));

        if (!batch.getBranch().getId().equals(branchId)) {
            throw new BadRequestException("Original batch belongs to a different branch for item: " + orderItem.getItemName());
        }
        if (!batch.getItem().getId().equals(orderItem.getItemId())) {
            throw new BadRequestException("Original batch does not match item: " + orderItem.getItemName());
        }

        batch.setQuantity(batch.getQuantity() + orderItem.getQty());
        stockBatchRepository.save(batch);
    }

    private void finalizeDineInSession(DiningTable diningTable) {
        pendingOrderRepository.findByTableId(diningTable.getId()).ifPresent(pendingOrder -> {
            pendingOrderItemRepository.deleteByPendingOrderId(pendingOrder.getId());
            pendingOrderRepository.delete(pendingOrder);
        });

        diningTable.setStatus(DiningTableStatus.AVAILABLE);
        diningTableRepository.save(diningTable);
    }

    private void addCashSaleToOpenShift(Order order) {
        double cashAmount = cashAmountForShift(order);
        if (cashAmount <= 0 || order.getStatus() != OrderStatus.COMPLETED) {
            return;
        }

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                        order.getBranchId(),
                        order.getCashierUserId(),
                        ShiftStatus.OPEN
                )
                .ifPresent(shift -> {
                    double currentCashSales = shift.getCashSales() == null ? 0.0 : shift.getCashSales();
                    shift.setCashSales(currentCashSales + cashAmount);
                    cashShiftRepository.save(shift);
                });
    }

    private void reverseCashSaleFromOpenShift(Order order) {
        double cashAmount = cashAmountForShift(order);
        if (cashAmount <= 0) {
            return;
        }

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                        order.getBranchId(),
                        order.getCashierUserId(),
                        ShiftStatus.OPEN
                )
                .filter(shift -> !order.getCreatedAt().isBefore(shift.getOpenedAt()))
                .ifPresent(shift -> {
                    double currentCashSales = shift.getCashSales() == null ? 0.0 : shift.getCashSales();
                    shift.setCashSales(Math.max(0.0, currentCashSales - cashAmount));
                    cashShiftRepository.save(shift);
                });
    }

    private double cashAmountForShift(Order order) {
        if (order == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(order.getPaidAmount(), order.getGrandTotal()));
    }

    private double roundMoney(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double roundRupee(double amount) {
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.HALF_UP).doubleValue();
    }

    private OrderResponse mapToOrderResponse(Order order, boolean includeItems) {
        List<OrderItem> items = includeItems ? orderItemRepository.findByOrderId(order.getId()) : Collections.emptyList();
        return buildOrderResponse(order, items);
    }

    private OrderResponse buildOrderResponse(Order order, List<OrderItem> items) {
        String customerName = "Walk-in Customer";
        if (order.getCustomerId() != null) {
            customerName = customerRepository.findById(order.getCustomerId())
                    .map(Customer::getName)
                    .orElse("Unknown Customer");
        }
        String cashierName = userRepository.findById(order.getCashierUserId())
                .map(User::getUsername)
                .orElse(null);

        List<OrderItemResponse> itemResponses = (items == null || items.isEmpty()) ? Collections.emptyList()
                : items.stream()
                .map(orderItem -> OrderItemResponse.builder()
                        .id(orderItem.getId())
                        .itemId(orderItem.getItemId())
                        .barcode(orderItem.getBarcode())
                        .itemName(orderItem.getItemName())
                        .altName(orderItem.getAltName())
                        .batchId(orderItem.getBatchId())
                        .qty(orderItem.getDisplayQty())
                        .qtyUnit(orderItem.getQtyUnit())
                        .unitPrice(orderItem.getUnitPrice())
                        .discountType(orderItem.getDiscountType() != null ? orderItem.getDiscountType().toString() : null)
                        .discountValue(orderItem.getDiscountValue())
                        .promotionId(orderItem.getPromotionId())
                        .promotionName(orderItem.getPromotionName())
                        .promotionDiscountAmount(orderItem.getPromotionDiscountAmount())
                        .promotionApplied(orderItem.getPromotionId() != null)
                        .finalUnitPrice(orderItem.getFinalUnitPrice())
                        .lineTotal(orderItem.getLineTotal())
                        .warrantyLabel(orderItem.getWarrantyLabel())
                        .warrantyPeriodValue(orderItem.getWarrantyPeriodValue())
                        .warrantyPeriodUnit(orderItem.getWarrantyPeriodUnit())
                        .build())
                .collect(Collectors.toList());

        String branchName = order.getReceiptBranchName();
        String branchAddress = order.getReceiptBranchAddress();
        String branchPhone = order.getReceiptBranchPhone();
        String branchLogo = order.getReceiptBranchLogo();

        if (order.getBranchId() != null && (branchName == null || branchAddress == null || branchPhone == null || branchLogo == null)) {
            Branch branch = branchRepository.findById(order.getBranchId()).orElse(null);
            if (branch != null) {
                if (branchName == null) branchName = branch.getName();
                if (branchAddress == null) branchAddress = branch.getAddress();
                if (branchPhone == null) branchPhone = branch.getPhone();
                if (branchLogo == null) branchLogo = branch.getLogo();
            }
        }

        // Return summary — cheaply computed for every order response
        long returnCount = orderReturnRepository.countByOriginalOrderId(order.getId());
        double totalReturnedAmount = orderReturnRepository
                .findByOriginalOrderIdOrderByCreatedAtDesc(order.getId())
                .stream()
                .mapToDouble(r -> r.getTotalRefundAmount())
                .sum();

        return OrderResponse.builder()
                .id(order.getId())
                .invoiceNo(order.getInvoiceNo())
                .branchId(order.getBranchId())
                .branchName(branchName)
                .branchAddress(branchAddress)
                .branchPhone(branchPhone)
                .branchLogo(branchLogo)
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
                .promotionDiscountTotal(order.getPromotionDiscountTotal())
                .billPromotionId(order.getBillPromotionId())
                .billPromotionName(order.getBillPromotionName())
                .billPromotionDiscountAmount(order.getBillPromotionDiscountAmount())
                .grandTotal(order.getGrandTotal())
                .paidAmount(order.getPaidAmount())
                .dueAmount(order.getDueAmount())
                .note(order.getNote())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .hasReturns(returnCount > 0)
                .returnCount((int) returnCount)
                .totalReturnedAmount(totalReturnedAmount)
                .build();
    }

    private List<Warranty> buildWarranties(Order order, List<OrderItem> orderItems, Customer customer) {
        if (orderItems == null || orderItems.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate startDate = order.getCreatedAt() == null ? LocalDate.now() : order.getCreatedAt().toLocalDate();
        String customerName = customer != null
                ? customer.getName()
                : order.getCustomerId() == null
                    ? "Walk-in Customer"
                    : customerRepository.findById(order.getCustomerId())
                        .map(Customer::getName)
                        .orElse("Unknown Customer");
        List<Warranty> warranties = new ArrayList<>();

        for (OrderItem item : orderItems) {
            Integer periodValue = item.getWarrantyPeriodValue();
            WarrantyPeriodUnit periodUnit = item.getWarrantyPeriodUnit();
            if (periodValue == null || periodValue <= 0 || periodUnit == null) {
                continue;
            }

            LocalDate endDate = calculateWarrantyEndDate(startDate, periodValue, periodUnit);
            String label = item.getWarrantyLabel();
            if (label == null || label.isBlank()) {
                label = periodValue + " " + periodUnit.name();
            }

            warranties.add(Warranty.builder()
                    .warrantyNo("WAR-" + order.getInvoiceNo() + "-" + item.getId())
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .branchId(order.getBranchId())
                    .invoiceNo(order.getInvoiceNo())
                    .customerId(order.getCustomerId())
                    .customerName(customerName)
                    .itemId(item.getItemId())
                    .itemName(item.getItemName())
                    .barcode(item.getBarcode())
                    .warrantyLabel(label)
                    .periodValue(periodValue)
                    .periodUnit(periodUnit)
                    .startDate(startDate)
                    .endDate(endDate)
                    .status(WarrantyStatus.ACTIVE)
                    .build());
        }

        return warranties;
    }

    private LocalDate calculateWarrantyEndDate(LocalDate startDate, int periodValue, WarrantyPeriodUnit periodUnit) {
        return switch (periodUnit) {
            case DAYS -> startDate.plusDays(periodValue);
            case MONTHS -> startDate.plusMonths(periodValue);
            case YEARS -> startDate.plusYears(periodValue);
        };
    }

    private void validateWarrantySelection(OrderItemRequest itemReq, Role role, Long branchId) {
        boolean hasLabel = itemReq.getWarrantyLabel() != null && !itemReq.getWarrantyLabel().isBlank();
        boolean hasPeriodValue = itemReq.getWarrantyPeriodValue() != null;
        boolean hasPeriodUnit = itemReq.getWarrantyPeriodUnit() != null;

        if ((hasLabel || hasPeriodValue || hasPeriodUnit) && !(hasLabel && hasPeriodValue && hasPeriodUnit)) {
            throw new BadRequestException("Warranty selection is incomplete");
        }
        if ((hasLabel || hasPeriodValue || hasPeriodUnit) && !appConfigurationService.isWarrantyAllowedForRole(role, branchId)) {
            throw new BadRequestException("Warranty selection is disabled for this role");
        }
    }

    private CustomerOrderListResponse map(Order order) {
        return CustomerOrderListResponse.builder()
                .id(order.getId())
                .invoiceNo(order.getInvoiceNo())
                .branchId(order.getBranchId())
                .orderType(order.getOrderType())
                .status(order.getStatus())
                .grandTotal(order.getGrandTotal())
                .paidAmount(order.getPaidAmount())
                .dueAmount(order.getDueAmount())
                .createdAt(order.getCreatedAt())
                .build();
    }

    private static final class PreparedOrderItem {
        private final OrderItem orderItem;
        private final List<StockUsageDraft> usages;
        private final List<StockOverrideDraft> overrides;

        private PreparedOrderItem(OrderItem orderItem, List<StockUsageDraft> usages, List<StockOverrideDraft> overrides) {
            this.orderItem = orderItem;
            this.usages = usages;
            this.overrides = overrides;
        }

        private OrderItem orderItem() {
            return orderItem;
        }
    }

    private static final class OfflineOrderMetadata {
        private final String clientSaleId;
        private final LocalDateTime offlineSoldAt;
        private final String invoiceNo;
        private final Long cashierUserId;

        private OfflineOrderMetadata(
                String clientSaleId,
                LocalDateTime offlineSoldAt,
                String invoiceNo,
                Long cashierUserId
        ) {
            this.clientSaleId = clientSaleId;
            this.offlineSoldAt = offlineSoldAt;
            this.invoiceNo = invoiceNo;
            this.cashierUserId = cashierUserId;
        }
    }

    private static final class StockConsumptionResult {
        private final Long primaryBatchId;
        private final double unitCost;
        private final double lineCost;
        private final List<StockUsageDraft> usages;
        private final List<StockOverrideDraft> overrides;

        private StockConsumptionResult(Long primaryBatchId, double unitCost, double lineCost, List<StockUsageDraft> usages, List<StockOverrideDraft> overrides) {
            this.primaryBatchId = primaryBatchId;
            this.unitCost = unitCost;
            this.lineCost = lineCost;
            this.usages = usages;
            this.overrides = overrides;
        }
    }

    private static final class InventoryConsumptionResult {
        private final Long primaryBatchId;
        private final double unitCost;
        private final double lineCost;
        private final List<StockUsageDraft> usages;
        private final List<StockOverrideDraft> overrides;

        private InventoryConsumptionResult(Long primaryBatchId, double unitCost, double lineCost, List<StockUsageDraft> usages, List<StockOverrideDraft> overrides) {
            this.primaryBatchId = primaryBatchId;
            this.unitCost = unitCost;
            this.lineCost = lineCost;
            this.usages = usages;
            this.overrides = overrides;
        }
    }

    private static final class StockUsageDraft {
        private final Long itemId;
        private final Long batchId;
        private final int quantity;

        private StockUsageDraft(Long itemId, Long batchId, int quantity) {
            this.itemId = itemId;
            this.batchId = batchId;
            this.quantity = quantity;
        }
    }

    private static final class StockOverrideDraft {
        private final Long saleItemId;
        private final String saleItemName;
        private final Long stockItemId;
        private final String stockItemName;
        private final Long batchId;
        private final int requiredQuantity;
        private final int availableQuantity;
        private final int shortageQuantity;
        private final MeasurementUnit qtyUnit;
        private final String reason;

        private StockOverrideDraft(
                Long saleItemId,
                String saleItemName,
                Long stockItemId,
                String stockItemName,
                Long batchId,
                int requiredQuantity,
                int availableQuantity,
                int shortageQuantity,
                MeasurementUnit qtyUnit,
                String reason
        ) {
            this.saleItemId = saleItemId;
            this.saleItemName = saleItemName;
            this.stockItemId = stockItemId;
            this.stockItemName = stockItemName;
            this.batchId = batchId;
            this.requiredQuantity = requiredQuantity;
            this.availableQuantity = availableQuantity;
            this.shortageQuantity = shortageQuantity;
            this.qtyUnit = qtyUnit;
            this.reason = reason;
        }
    }

    private static final class StockOverrideContext {
        private final StockOverrideMode mode;
        private final boolean allowed;
        private final boolean overrideAvailable;
        private final String reason;

        private StockOverrideContext(StockOverrideMode mode, boolean allowed, boolean overrideAvailable, String reason) {
            this.mode = mode;
            this.allowed = allowed;
            this.overrideAvailable = overrideAvailable;
            this.reason = reason;
        }
    }
}
