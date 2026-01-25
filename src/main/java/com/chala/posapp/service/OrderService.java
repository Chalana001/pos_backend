package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.chala.posapp.entity.Role.CASHIER;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final InvoiceService invoiceService;
    private final CustomerRepository customerRepository;
    private final ShiftService shiftService;

    // ⚠️ StockRepository අයින් කරලා Batch Repository එක දැම්මා
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository; // To get Branch entity for returns

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {

        // Get logged user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Long branchId;
        if (user.getRole() == CASHIER) {
            branchId = user.getBranchId();
        } else {
            if (request.getBranchId() == null) throw new RuntimeException("BranchId required");
            branchId = request.getBranchId();
        }

        if (request.getItems() == null || request.getItems().isEmpty())
            throw new RuntimeException("Order items required");

        if (request.getOrderType() == OrderType.CREDIT && request.getCustomerId() == null)
            throw new RuntimeException("Customer required for CREDIT order");

        // Create invoice
        String invoiceNo = invoiceService.generateInvoiceNo(branchId);

        // Calculate totals
        double subTotal = 0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();

        // --- 1. VALIDATION & CALCULATION LOOP ---
        for (OrderItemRequest itemReq : request.getItems()) {
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found: " + itemReq.getItemId()));

            if (!item.isActive())
                throw new RuntimeException("Item is inactive: " + item.getBarcode());

            // 🚀 Batch System Check: Total Qty Available?
            Integer currentTotalStock = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
            if (currentTotalStock == null || currentTotalStock < itemReq.getQty()) {
                throw new RuntimeException("Not enough stock for item: " + item.getName() +
                        " (Available: " + (currentTotalStock == null ? 0 : currentTotalStock) + ")");
            }

            // unit price
            double unitPrice = itemReq.getUnitPrice() > 0 ? itemReq.getUnitPrice() : item.getSellingPrice().doubleValue();

            // discount logic
            DiscountType discountType = itemReq.getDiscountType() == null ? DiscountType.NONE : itemReq.getDiscountType();
            double discountValue = itemReq.getDiscountValue();

            double finalUnitPrice = calculateFinalUnitPrice(unitPrice, discountType, discountValue);
            double lineTotal = finalUnitPrice * itemReq.getQty();

            OrderItem oi = OrderItem.builder()
                    .orderId(null) // set after order saved
                    .itemId(item.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(itemReq.getQty())
                    .unitPrice(unitPrice)
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .finalUnitPrice(finalUnitPrice)
                    .lineTotal(lineTotal)
                    .build();

            orderItemsToSave.add(oi);
            subTotal += lineTotal;
        }

        // --- 2. BILL DISCOUNT & PAYMENT LOGIC ---
        double billDiscount = request.getBillDiscount();
        if (billDiscount < 0) billDiscount = 0;
        if (billDiscount > subTotal) billDiscount = subTotal;

        double grandTotal = subTotal - billDiscount;

        double paidAmount = request.getPaidAmount();
        if (paidAmount < 0) paidAmount = 0;

        double dueAmount;
        if (request.getOrderType() == OrderType.CREDIT) {
            paidAmount = 0;
            dueAmount = grandTotal;
        } else {
            if (paidAmount < grandTotal)
                throw new RuntimeException("Paid amount cannot be less than grand total for CASH sale");
            dueAmount = 0;
        }

        Order order = Order.builder()
                .invoiceNo(invoiceNo)
                .branchId(branchId)
                .cashierUserId(user.getId())
                .customerId(request.getCustomerId())
                .orderType(request.getOrderType())
                .status(OrderStatus.COMPLETED)
                .subTotal(subTotal)
                .billDiscount(billDiscount)
                .grandTotal(grandTotal)
                .paidAmount(paidAmount)
                .dueAmount(dueAmount)
                .note(request.getNote())
                .build();

        Order savedOrder = orderRepository.save(order);

        // Save order items
        for (OrderItem oi : orderItemsToSave) {
            oi.setOrderId(savedOrder.getId());
        }
        orderItemRepository.saveAll(orderItemsToSave);

        // --- 3. CUSTOMER CREDIT LOGIC ---
        if (savedOrder.getOrderType() == OrderType.CREDIT) {
            Customer customer = customerRepository.findById(savedOrder.getCustomerId())
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            if (customer.getCreditLimit() != null) {
                double max = customer.getCreditLimit();
                if (customer.getDueAmount() + savedOrder.getDueAmount() > max) {
                    throw new RuntimeException("Customer credit limit exceeded");
                }
            }
            customer.setDueAmount(customer.getDueAmount() + savedOrder.getDueAmount());
            customerRepository.save(customer);
        }

        // --- 4. STOCK REDUCTION (FIFO) 🚀 ---
        for (OrderItem oi : orderItemsToSave) {
            int qtyNeeded = oi.getQty();

            // පරණම Batches ටික අරගෙන අඩු කරගෙන යන්න
            List<StockBatch> batches = stockBatchRepository.findAvailableBatches(branchId, oi.getItemId());

            for (StockBatch batch : batches) {
                if (qtyNeeded == 0) break;

                int available = batch.getQuantity();

                if (available >= qtyNeeded) {
                    batch.setQuantity(available - qtyNeeded);
                    qtyNeeded = 0;
                } else {
                    batch.setQuantity(0);
                    qtyNeeded -= available;
                }
                stockBatchRepository.save(batch);
            }
        }

        // Update shift
        if (request.getOrderType() == OrderType.CASH) {
            shiftService.addCashSale(branchId, request.getPaidAmount());
        }

        return buildOrderResponse(savedOrder, orderItemsToSave);
    }

    @Transactional
    public OrderResponse cancelOrder(String invoiceNo, CancelOrderRequest request) {

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (order.getStatus() == OrderStatus.CANCELED)
            throw new RuntimeException("Order already canceled");

        // Permission Check
        if (user.getRole() != Role.ADMIN && !order.getBranchId().equals(user.getBranchId()))
            throw new RuntimeException("Cannot cancel other branch order");

        // --- 1. CREDIT ROLLBACK ---
        if (order.getOrderType() == OrderType.CREDIT && order.getCustomerId() != null) {
            Customer customer = customerRepository.findById(order.getCustomerId())
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            double newDue = customer.getDueAmount() - order.getDueAmount();
            if (newDue < 0) newDue = 0;

            customer.setDueAmount(newDue);
            customerRepository.save(customer);
        }

        // --- 2. UPDATE ORDER STATUS ---
        order.setStatus(OrderStatus.CANCELED);
        order.setCancelReason(request.getReason());
        order.setCanceledAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);

        // --- 3. STOCK ROLLBACK (CREATE RETURN BATCHES) 🚀 ---
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Branch branch = branchRepository.findById(order.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        for (OrderItem oi : items) {
            Item item = itemRepository.findById(oi.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found"));

            // අලුත් Batch එකක් හදනවා "Sales Return" විදියට
            // Batch Code: "RTN-{InvoiceNo}"
            StockBatch returnBatch = StockBatch.builder()
                    .branch(branch)
                    .item(item)
                    .quantity(oi.getQty())
                    .originalQuantity(oi.getQty())
                    .costPrice(item.getCostPrice()) // Current master cost price
                    .sellingPrice(item.getSellingPrice())
                    .batchCode("RTN-" + order.getInvoiceNo()) // Traceability
                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(returnBatch);
        }

        return buildOrderResponse(saved, items);
    }

    // --- READ METHODS ---

    public OrderResponse getOrder(String invoiceNo) {
        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        return buildOrderResponse(order, items);
    }

    public Page<CustomerOrderListResponse> list(Long customerId, String orderType, Pageable pageable) {
        customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        Page<Order> page;
        if (orderType == null || orderType.equalsIgnoreCase("ALL")) {
            page = orderRepository.findByCustomerId(customerId, pageable);
        } else {
            OrderType type = OrderType.valueOf(orderType.toUpperCase());
            page = orderRepository.findByCustomerIdAndOrderType(customerId, type, pageable);
        }
        return page.map(this::map);
    }

    // --- HELPERS ---

    private double calculateFinalUnitPrice(double unitPrice, DiscountType type, double value) {
        if (type == null || type == DiscountType.NONE) return unitPrice;

        if (type == DiscountType.PERCENT) {
            if (value < 0) value = 0;
            if (value > 100) value = 100;
            return unitPrice - (unitPrice * (value / 100.0));
        }

        // FIXED AMOUNT
        if (value < 0) value = 0;
        if (value > unitPrice) value = unitPrice;
        return unitPrice - value;
    }

    private OrderResponse buildOrderResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(oi -> OrderItemResponse.builder()
                        .itemId(oi.getItemId())
                        .barcode(oi.getBarcode())
                        .itemName(oi.getItemName())
                        .qty(oi.getQty())
                        .unitPrice(oi.getUnitPrice())
                        .discountType(oi.getDiscountType())
                        .discountValue(oi.getDiscountValue())
                        .finalUnitPrice(oi.getFinalUnitPrice())
                        .lineTotal(oi.getLineTotal())
                        .build())
                .toList();

        return OrderResponse.builder()
                .id(order.getId())
                .invoiceNo(order.getInvoiceNo())
                .branchId(order.getBranchId())
                .cashierUserId(order.getCashierUserId())
                .customerId(order.getCustomerId())
                .orderType(order.getOrderType())
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

    private CustomerOrderListResponse map(Order o) {
        return CustomerOrderListResponse.builder()
                .id(o.getId())
                .invoiceNo(o.getInvoiceNo())
                .branchId(o.getBranchId())
                .orderType(o.getOrderType())
                .status(o.getStatus())
                .grandTotal(o.getGrandTotal())
                .paidAmount(o.getPaidAmount())
                .dueAmount(o.getDueAmount())
                .createdAt(o.getCreatedAt())
                .build();
    }
}