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
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
    private final UserRepository userRepository;
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

    private void ensureBranchAccess(User user, Long branchId) {
        if (isAdminLike(user)) {
            return;
        }

        Long userBranchId = requireAssignedBranch(user);
        if (!userBranchId.equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        return createOrderInternal(request, null);
    }

    @Transactional
    public OfflineSaleImportResponse importOfflineSale(OfflineSaleImportRequest request) {
        User user = getLoggedUser();
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
                new OfflineOrderMetadata(request.getClientSaleId(), request.getOfflineSoldAt())
        );

        return OfflineSaleImportResponse.builder()
                .clientSaleId(request.getClientSaleId())
                .success(true)
                .orderId(response.getId())
                .invoiceNo(response.getInvoiceNo())
                .message("Imported")
                .build();
    }

    @Transactional
    public List<OfflineSaleImportResponse> importOfflineSales(List<OfflineSaleImportRequest> requests) {
        List<OfflineSaleImportResponse> results = new ArrayList<>();
        for (OfflineSaleImportRequest request : requests) {
            try {
                results.add(importOfflineSale(request));
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
        User user = getLoggedUser();

        Long branchId;
        if (user.getRole() == Role.CASHIER || user.getRole() == Role.MANAGER) {
            branchId = requireAssignedBranch(user);
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

        SaleMode saleMode = request.getSaleMode() == null ? SaleMode.TAKEAWAY : request.getSaleMode();
        DiningTable diningTable = resolveDiningTable(user, branchId, saleMode, request.getTableId());

        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        String invoiceNo = invoiceService.generateInvoiceNo(branchId);

        double subTotal = 0;
        List<PreparedOrderItem> preparedItems = new ArrayList<>();
        Map<Long, StockBatch> batchesToUpdate = new LinkedHashMap<>();

        for (OrderItemRequest itemReq : request.getItems()) {
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemReq.getItemId()));

            if (!item.isActive()) {
                throw new BadRequestException("Item is inactive: " + item.getBarcode());
            }

            int normalizedQty = QuantityConversionUtil.normalizeQuantity(item, itemReq.getQty(), itemReq.getQtyUnit());
            StockConsumptionResult consumption = consumeStockForSale(
                    branchId,
                    item,
                    itemReq,
                    normalizedQty,
                    batchesToUpdate,
                    offlineOrderMetadata != null
            );

            double unitPrice = itemReq.getUnitPrice();
            DiscountType discountType = itemReq.getDiscountType() == null ? DiscountType.NONE : itemReq.getDiscountType();
            double discountValue = itemReq.getDiscountValue();
            double finalUnitPrice = calculateFinalUnitPrice(unitPrice, discountType, discountValue);
            double lineTotal = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(finalUnitPrice), normalizedQty).doubleValue();

            OrderItem orderItem = OrderItem.builder()
                    .itemId(item.getId())
                    .batchId(consumption.primaryBatchId)
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .itemType(item.getItemType())
                    .qty(normalizedQty)
                    .displayQty(itemReq.getQty().stripTrailingZeros())
                    .qtyUnit(resolveQtyUnit(item, itemReq))
                    .unitPrice(unitPrice)
                    .costPrice(consumption.unitCost)
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .finalUnitPrice(finalUnitPrice)
                    .lineCost(consumption.lineCost)
                    .lineTotal(lineTotal)
                    .build();

            preparedItems.add(new PreparedOrderItem(orderItem, consumption.usages));
            subTotal += lineTotal;
        }

        double billDiscount = request.getBillDiscount();
        if (billDiscount < 0) billDiscount = 0;
        if (billDiscount > subTotal) billDiscount = subTotal;

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
                .cashierUserId(user.getId())
                .customerId(request.getCustomerId())
                .orderType(effectiveOrderType)
                .paymentMethod(paymentMethod)
                .saleMode(saleMode)
                .tableId(diningTable != null ? diningTable.getId() : null)
                .tableName(diningTable != null ? diningTable.getTableName() : null)
                .status(OrderStatus.COMPLETED)
                .subTotal(subTotal)
                .billDiscount(billDiscount)
                .grandTotal(grandTotal)
                .paidAmount(paidAmount)
                .dueAmount(dueAmount)
                .salePaidAmount(paidTowardSale)
                .saleDueAmount(dueAmount)
                .note(request.getNote())
                .createdAt(LocalDateTime.now())
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

        return buildOrderResponse(savedOrder, savedOrderItems);
    }

    @Transactional
    public OrderResponse cancelOrder(String invoiceNo, CancelOrderRequest request) {
        User user = getLoggedUser();

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
        Order saved = orderRepository.save(order);
        reverseCashSaleFromOpenShift(saved);

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        for (OrderItem orderItem : items) {
            List<OrderItemStockUsage> usages = orderItemStockUsageRepository.findByOrderItemId(orderItem.getId());
            if (!usages.isEmpty()) {
                restoreStockUsages(usages, order.getBranchId());
            } else {
                restoreStockToOriginalBatch(orderItem, order.getBranchId());
            }
        }

        return buildOrderResponse(saved, items);
    }

    public OrderResponse getOrder(String invoiceNo) {
        User user = getLoggedUser();
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        ensureBranchAccess(user, order.getBranchId());
        return mapToOrderResponse(order, true);
    }

    @Transactional
    public OrderResponse recordPayment(String invoiceNo, OrderPaymentRequest request) {
        User user = getLoggedUser();
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
        User user = getLoggedUser();

        if (!isAdminLike(user) && user.getRole() != Role.MANAGER) {
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

        if (!isAdminLike(user)) {
            Long userBranchId = requireAssignedBranch(user);
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
            boolean allowAutomaticBatchSelection
    ) {
        if (item.getItemType() == ItemType.SERVICE) {
            if (!branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
                throw new BadRequestException("Service item is not assigned to this branch: " + item.getName());
            }
            double unitCost = item.getCostPrice() != null ? item.getCostPrice().doubleValue() : 0.0;
            double lineCost = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(unitCost), normalizedQty).doubleValue();
            return new StockConsumptionResult(null, unitCost, lineCost, List.of());
        }

        if (item.getItemType() == ItemType.RECIPE) {
            List<RecipeIngredient> recipeIngredients = recipeIngredientRepository.findByParentItemId(item.getId());
            if (recipeIngredients.isEmpty()) {
                throw new BadRequestException("Recipe item has no ingredients: " + item.getName());
            }

            Map<Long, Item> ingredientMap = itemRepository.findAllById(
                            recipeIngredients.stream()
                                    .map(RecipeIngredient::getIngredientId)
                                    .distinct()
                                    .toList()
                    ).stream()
                    .collect(Collectors.toMap(Item::getId, ingredient -> ingredient));

            List<StockUsageDraft> usages = new ArrayList<>();
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
                        ingredientItem,
                        null,
                        (int) requiredQty,
                        batchesToUpdate
                );
                usages.addAll(ingredientConsumption.usages);
                lineCost += ingredientConsumption.lineCost;
            }

            double unitCost = normalizedQty == 0
                    ? 0.0
                    : BigDecimal.valueOf(lineCost)
                    .divide(BigDecimal.valueOf(normalizedQty), 6, RoundingMode.HALF_UP)
                    .doubleValue();

            return new StockConsumptionResult(null, unitCost, lineCost, usages);
        }

        if (isFreePlanWithoutStockManagement()) {
            double unitCost = item.getCostPrice() != null ? item.getCostPrice().doubleValue() : 0.0;
            double lineCost = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(unitCost), normalizedQty).doubleValue();
            return new StockConsumptionResult(itemReq.getBatchId(), unitCost, lineCost, List.of());
        }

        if (itemReq.getBatchId() == null && !allowAutomaticBatchSelection) {
            throw new BadRequestException("Batch ID is missing for item: " + item.getName());
        }

        InventoryConsumptionResult inventoryConsumption = consumeInventoryItem(
                branchId,
                item,
                itemReq.getBatchId(),
                normalizedQty,
                batchesToUpdate
        );

        return new StockConsumptionResult(
                inventoryConsumption.primaryBatchId,
                inventoryConsumption.unitCost,
                inventoryConsumption.lineCost,
                inventoryConsumption.usages
        );
    }

    private InventoryConsumptionResult consumeInventoryItem(
            Long branchId,
            Item item,
            Long explicitBatchId,
            int normalizedQty,
            Map<Long, StockBatch> batchesToUpdate
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

            decrementBatch(batch, normalizedQty);
            batchesToUpdate.put(batch.getId(), batch);

            double lineCost = QuantityConversionUtil.calculateActualAmount(item, batch.getCostPrice(), normalizedQty).doubleValue();
            return new InventoryConsumptionResult(
                    batch.getId(),
                    batch.getCostPrice().doubleValue(),
                    lineCost,
                    List.of(new StockUsageDraft(item.getId(), batch.getId(), normalizedQty))
            );
        }

        List<StockBatch> availableBatches = stockBatchRepository.findAvailableBatches(branchId, item.getId());
        if (availableBatches.isEmpty()) {
            throw new BadRequestException("Insufficient stock for " + item.getName());
        }

        int remainingQty = normalizedQty;
        List<StockUsageDraft> usages = new ArrayList<>();
        double lineCost = 0.0;

        for (StockBatch batch : availableBatches) {
            if (remainingQty <= 0) {
                break;
            }

            int availableQty = batch.getQuantity() == null ? 0 : batch.getQuantity();
            if (availableQty <= 0) {
                continue;
            }

            int usedQty = Math.min(availableQty, remainingQty);
            decrementBatch(batch, usedQty);
            batchesToUpdate.put(batch.getId(), batch);

            usages.add(new StockUsageDraft(item.getId(), batch.getId(), usedQty));
            lineCost += QuantityConversionUtil.calculateActualAmount(item, batch.getCostPrice(), usedQty).doubleValue();
            remainingQty -= usedQty;
        }

        if (remainingQty > 0) {
            throw new BadRequestException("Insufficient stock for " + item.getName());
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
                usages
        );
    }

    private boolean isFreePlanWithoutStockManagement() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return false;
        }
        return tenantSubscriptionRepository.findByTenantId(tenantId)
                .map(TenantSubscription::getPlan)
                .map(plan -> plan.getName() == null ? "" : plan.getName().trim().toUpperCase())
                .map(planName -> planName.equals("FREE") || planName.equals("MONTHLY_DEMO"))
                .orElse(false);
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

    private void decrementBatch(StockBatch batch, int normalizedQty) {
        if (batch.getQuantity() < normalizedQty) {
            throw new BadRequestException("Insufficient stock for " + batch.getItem().getName()
                    + " (Batch #" + batch.getId() + "). Available: " + batch.getQuantity());
        }
        batch.setQuantity(batch.getQuantity() - normalizedQty);
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
        if (item.getItemType() == ItemType.WEIGHT) {
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
                        .itemId(orderItem.getItemId())
                        .barcode(orderItem.getBarcode())
                        .itemName(orderItem.getItemName())
                        .batchId(orderItem.getBatchId())
                        .qty(orderItem.getDisplayQty())
                        .qtyUnit(orderItem.getQtyUnit())
                        .unitPrice(orderItem.getUnitPrice())
                        .discountType(orderItem.getDiscountType() != null ? orderItem.getDiscountType().toString() : null)
                        .discountValue(orderItem.getDiscountValue())
                        .finalUnitPrice(orderItem.getFinalUnitPrice())
                        .lineTotal(orderItem.getLineTotal())
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
                .grandTotal(order.getGrandTotal())
                .paidAmount(order.getPaidAmount())
                .dueAmount(order.getDueAmount())
                .note(order.getNote())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
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

        private PreparedOrderItem(OrderItem orderItem, List<StockUsageDraft> usages) {
            this.orderItem = orderItem;
            this.usages = usages;
        }

        private OrderItem orderItem() {
            return orderItem;
        }
    }

    private static final class OfflineOrderMetadata {
        private final String clientSaleId;
        private final LocalDateTime offlineSoldAt;

        private OfflineOrderMetadata(String clientSaleId, LocalDateTime offlineSoldAt) {
            this.clientSaleId = clientSaleId;
            this.offlineSoldAt = offlineSoldAt;
        }
    }

    private static final class StockConsumptionResult {
        private final Long primaryBatchId;
        private final double unitCost;
        private final double lineCost;
        private final List<StockUsageDraft> usages;

        private StockConsumptionResult(Long primaryBatchId, double unitCost, double lineCost, List<StockUsageDraft> usages) {
            this.primaryBatchId = primaryBatchId;
            this.unitCost = unitCost;
            this.lineCost = lineCost;
            this.usages = usages;
        }
    }

    private static final class InventoryConsumptionResult {
        private final Long primaryBatchId;
        private final double unitCost;
        private final double lineCost;
        private final List<StockUsageDraft> usages;

        private InventoryConsumptionResult(Long primaryBatchId, double unitCost, double lineCost, List<StockUsageDraft> usages) {
            this.primaryBatchId = primaryBatchId;
            this.unitCost = unitCost;
            this.lineCost = lineCost;
            this.usages = usages;
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
}
