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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {

        // 1. Get Logged User & Branch
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Long branchId;
        if (user.getRole() == Role.CASHIER) {
            branchId = user.getBranchId();
        } else {
            if (request.getBranchId() == null) throw new RuntimeException("BranchId required");
            branchId = request.getBranchId();
        }

        // 2. Basic Validations
        if (request.getItems() == null || request.getItems().isEmpty())
            throw new RuntimeException("Order items required");

        if (request.getOrderType() == OrderType.CREDIT && request.getCustomerId() == null)
            throw new RuntimeException("Customer required for CREDIT order");

        // 3. Generate Invoice No
        String invoiceNo = invoiceService.generateInvoiceNo(branchId);

        // Variables for calculation
        double subTotal = 0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();
        List<StockBatch> batchesToUpdate = new ArrayList<>();

        // --- 4. PROCESSING ITEMS (Specific Batch Logic) ---
        for (OrderItemRequest itemReq : request.getItems()) {

            // A. Validate Item
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found: " + itemReq.getItemId()));

            if (!item.isActive())
                throw new RuntimeException("Item is inactive: " + item.getBarcode());

            // B. 🔥 VALIDATE BATCH (Must exist and belong to branch/item)
            if (itemReq.getBatchId() == null) {
                throw new RuntimeException("Batch ID is missing for item: " + item.getName());
            }

            StockBatch batch = stockBatchRepository.findById(itemReq.getBatchId())
                    .orElseThrow(() -> new RuntimeException("Batch not found ID: " + itemReq.getBatchId()));

            if (!batch.getItem().getId().equals(item.getId())) {
                throw new RuntimeException("Batch ID does not match Item ID");
            }
            if (!batch.getBranch().getId().equals(branchId)) {
                throw new RuntimeException("Batch does not belong to this branch");
            }

            // C. Check Stock Availability
            if (batch.getQuantity() < itemReq.getQty()) {
                throw new RuntimeException("Insufficient stock for " + item.getName() +
                        " (Batch #" + batch.getId() + "). Available: " + batch.getQuantity());
            }

            // D. Deduct Stock (In Memory)
            batch.setQuantity(batch.getQuantity() - itemReq.getQty());
            batchesToUpdate.add(batch);

            // E. Calculations
            double unitPrice = itemReq.getUnitPrice(); // Price from Frontend (Selected Batch Price)
            double costPrice = batch.getCostPrice().doubleValue();  // 🔥 Capture Cost for Profit Report

            DiscountType discountType = itemReq.getDiscountType() == null ? DiscountType.NONE : itemReq.getDiscountType();
            double discountValue = itemReq.getDiscountValue();

            double finalUnitPrice = calculateFinalUnitPrice(unitPrice, discountType, discountValue);
            double lineTotal = finalUnitPrice * itemReq.getQty();

            // F. Build OrderItem
            OrderItem oi = OrderItem.builder()
                    .itemId(item.getId())
                    .batchId(batch.getId())       // ✅ Save Batch ID
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(itemReq.getQty())
                    .unitPrice(unitPrice)
                    .costPrice(costPrice)         // ✅ Save Cost Price
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .finalUnitPrice(finalUnitPrice)
                    .lineTotal(lineTotal)
                    .build();

            orderItemsToSave.add(oi);
            subTotal += lineTotal;
        }

        // --- 5. BILL DISCOUNT & TOTALS ---
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

        // --- 6. SAVE ORDER ---
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
                .createdAt(LocalDateTime.now())
                .build();

        Order savedOrder = orderRepository.save(order);

        // --- 7. SAVE ITEMS & UPDATE STOCK ---
        for (OrderItem oi : orderItemsToSave) {
            oi.setOrderId(savedOrder.getId());
        }
        orderItemRepository.saveAll(orderItemsToSave);
        stockBatchRepository.saveAll(batchesToUpdate);

        // --- 8. CUSTOMER CREDIT ---
        if (savedOrder.getOrderType() == OrderType.CREDIT) {
            Customer customer = customerRepository.findById(savedOrder.getCustomerId())
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            if (customer.getCreditLimit() != null && customer.getCreditLimit() > 0) {
                if (customer.getDueAmount() + savedOrder.getDueAmount() > customer.getCreditLimit()) {
                    throw new RuntimeException("Customer credit limit exceeded");
                }
            }
            customer.setDueAmount(customer.getDueAmount() + savedOrder.getDueAmount());
            customerRepository.save(customer);
        }

        // --- 9. UPDATE SHIFT ---
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

        if (user.getRole() != Role.ADMIN && !order.getBranchId().equals(user.getBranchId()))
            throw new RuntimeException("Cannot cancel other branch order");

        // 1. Credit Rollback
        if (order.getOrderType() == OrderType.CREDIT && order.getCustomerId() != null) {
            Customer customer = customerRepository.findById(order.getCustomerId())
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            double newDue = customer.getDueAmount() - order.getDueAmount();
            customer.setDueAmount(Math.max(0, newDue));
            customerRepository.save(customer);
        }

        // 2. Update Status
        order.setStatus(OrderStatus.CANCELED);
        order.setCancelReason(request.getReason());
        order.setCanceledAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);

        // 3. Stock Rollback (Create Return Batch)
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Branch branch = branchRepository.findById(order.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        for (OrderItem oi : items) {
            Item item = itemRepository.findById(oi.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found"));

            // Note: We use the COST PRICE from the OrderItem (historical cost), not current Item cost
            StockBatch returnBatch = StockBatch.builder()
                    .branch(branch)
                    .item(item)
                    .quantity(oi.getQty())
                    .originalQuantity(oi.getQty())
                    .costPrice(BigDecimal.valueOf(oi.getCostPrice())) // ✅ Use historical cost
                    .sellingPrice(item.getSellingPrice()) // Use current selling price (or oi.unitPrice)
                    .batchCode("RTN-" + order.getInvoiceNo())
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

        if (value < 0) value = 0;
        if (value > unitPrice) value = unitPrice;
        return unitPrice - value;
    }

    // ✅ FIXED: Only ONE buildOrderResponse method
    private OrderResponse buildOrderResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(oi -> OrderItemResponse.builder()
                        .itemId(oi.getItemId())
                        .barcode(oi.getBarcode())
                        .itemName(oi.getItemName())
                        .batchId(oi.getBatchId()) // ✅ Mapped Batch ID
                        .qty(oi.getQty())
                        .unitPrice(oi.getUnitPrice())
                        .discountType(oi.getDiscountType().toString()) // Enum to String
                        .discountValue(oi.getDiscountValue())
                        .finalUnitPrice(oi.getFinalUnitPrice())
                        .lineTotal(oi.getLineTotal())
                        .build())
                .collect(Collectors.toList());

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