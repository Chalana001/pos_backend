package com.chala.posapp.service;

import com.chala.posapp.dto.customer.CustomerOrderListResponse;
import com.chala.posapp.dto.order.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final InvoiceService invoiceService;
    private final CustomerRepository customerRepository;
    private final CashShiftRepository cashShiftRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;

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

        if (request.getItems() == null || request.getItems().isEmpty())
            throw new BadRequestException("Order items required");

        if (request.getOrderType() == OrderType.CREDIT && request.getCustomerId() == null)
            throw new BadRequestException("Customer required for CREDIT order");

        String invoiceNo = invoiceService.generateInvoiceNo(branchId);

        double subTotal = 0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();
        List<StockBatch> batchesToUpdate = new ArrayList<>();

        for (OrderItemRequest itemReq : request.getItems()) {

            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemReq.getItemId()));

            if (!item.isActive())
                throw new BadRequestException("Item is inactive: " + item.getBarcode());

            if (itemReq.getBatchId() == null) {
                throw new BadRequestException("Batch ID is missing for item: " + item.getName());
            }

            StockBatch batch = stockBatchRepository.findById(itemReq.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Batch not found ID: " + itemReq.getBatchId()));

            if (!batch.getItem().getId().equals(item.getId())) {
                throw new BadRequestException("Batch ID does not match Item ID");
            }
            if (!batch.getBranch().getId().equals(branchId)) {
                throw new BadRequestException("Batch does not belong to this branch");
            }

            if (batch.getQuantity() < itemReq.getQty()) {
                throw new BadRequestException("Insufficient stock for " + item.getName() +
                        " (Batch #" + batch.getId() + "). Available: " + batch.getQuantity());
            }

            batch.setQuantity(batch.getQuantity() - itemReq.getQty());
            batchesToUpdate.add(batch);

            double unitPrice = itemReq.getUnitPrice();
            double costPrice = batch.getCostPrice().doubleValue();

            DiscountType discountType = itemReq.getDiscountType() == null ? DiscountType.NONE : itemReq.getDiscountType();
            double discountValue = itemReq.getDiscountValue();

            double finalUnitPrice = calculateFinalUnitPrice(unitPrice, discountType, discountValue);
            double lineTotal = finalUnitPrice * itemReq.getQty();

            OrderItem oi = OrderItem.builder()
                    .itemId(item.getId())
                    .batchId(batch.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(itemReq.getQty())
                    .unitPrice(unitPrice)
                    .costPrice(costPrice)
                    .discountType(discountType)
                    .discountValue(discountValue)
                    .finalUnitPrice(finalUnitPrice)
                    .lineTotal(lineTotal)
                    .build();

            orderItemsToSave.add(oi);
            subTotal += lineTotal;
        }

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
                throw new BadRequestException("Paid amount cannot be less than grand total for CASH sale");
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
                .createdAt(LocalDateTime.now())
                .build();

        Order savedOrder = orderRepository.save(order);

        for (OrderItem oi : orderItemsToSave) {
            oi.setOrderId(savedOrder.getId());
        }
        orderItemRepository.saveAll(orderItemsToSave);
        stockBatchRepository.saveAll(batchesToUpdate);

        if (savedOrder.getOrderType() == OrderType.CREDIT) {
            Customer customer = customerRepository.findById(savedOrder.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

            if (customer.getCreditLimit() != null && customer.getCreditLimit() > 0) {
                if (customer.getDueAmount() + savedOrder.getDueAmount() > customer.getCreditLimit()) {
                    throw new BadRequestException("Customer credit limit exceeded");
                }
            }
            customer.setDueAmount(customer.getDueAmount() + savedOrder.getDueAmount());
            customerRepository.save(customer);
        }

        addCashSaleToOpenShift(savedOrder);

        return buildOrderResponse(savedOrder, orderItemsToSave);
    }

    @Transactional
    public OrderResponse cancelOrder(String invoiceNo, CancelOrderRequest request) {

        User user = getLoggedUser();

        Order order = orderRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() == OrderStatus.CANCELED)
            throw new AlreadyExistsException("Order already canceled");

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
        Branch branch = branchRepository.findById(order.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        for (OrderItem oi : items) {
            Item item = itemRepository.findById(oi.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

            StockBatch returnBatch = StockBatch.builder()
                    .branch(branch)
                    .item(item)
                    .quantity(oi.getQty())
                    .originalQuantity(oi.getQty())
                    .costPrice(BigDecimal.valueOf(oi.getCostPrice()))
                    .sellingPrice(item.getSellingPrice())
                    .batchCode("RTN-" + order.getInvoiceNo())
                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(returnBatch);
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

    public Page<OrderResponse> getAllOrders(String search, int page, int size, String branchIdString) {

        User user = getLoggedUser();

        if (!isAdminLike(user) && user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Access denied: Only Admin or Manager can perform this action.");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Order> orderPage;

        Long branchId = 0L;
        if (branchIdString != null && !branchIdString.isEmpty()) {
            try {
                branchId = Long.parseLong(branchIdString);
            } catch (NumberFormatException e) {
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

        boolean hasSearch = (search != null && !search.isEmpty());

        if (hasSearch && branchId != 0L) {
            orderPage = orderRepository.findByInvoiceNoContainingIgnoreCaseAndBranchId(search, branchId, pageable);

        } else if (hasSearch && branchId == 0L) {
            orderPage = orderRepository.findByInvoiceNoContainingIgnoreCase(search, pageable);

        } else if (!hasSearch && branchId != 0L) {
            orderPage = orderRepository.findByBranchId(branchId, pageable);

        } else {
            orderPage = orderRepository.findAll(pageable);
        }

        return orderPage.map(order -> mapToOrderResponse(order, false));
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

    private void addCashSaleToOpenShift(Order order) {
        if (order.getOrderType() != OrderType.CASH || order.getStatus() != OrderStatus.COMPLETED) {
            return;
        }

        cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(
                        order.getBranchId(),
                        order.getCashierUserId(),
                        ShiftStatus.OPEN
                )
                .ifPresent(shift -> {
                    double currentCashSales = shift.getCashSales() == null ? 0.0 : shift.getCashSales();
                    shift.setCashSales(currentCashSales + order.getGrandTotal());
                    cashShiftRepository.save(shift);
                });
    }

    private void reverseCashSaleFromOpenShift(Order order) {
        if (order.getOrderType() != OrderType.CASH) {
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
                    shift.setCashSales(Math.max(0.0, currentCashSales - order.getGrandTotal()));
                    cashShiftRepository.save(shift);
                });
    }

    private OrderResponse mapToOrderResponse(Order order, boolean includeItems) {
        List<OrderItem> items = includeItems ? orderItemRepository.findByOrderId(order.getId()) : Collections.emptyList();
        return buildOrderResponse(order, items);
    }

    // Main Builder
    private OrderResponse buildOrderResponse(Order order, List<OrderItem> items) {

        String customerName = "Walk-in Customer";
        if (order.getCustomerId() != null) {
            customerName = customerRepository.findById(order.getCustomerId())
                    .map(customer -> customer.getName())
                    .orElse("Unknown Customer");
        }

        List<OrderItemResponse> itemResponses = (items == null || items.isEmpty()) ? Collections.emptyList() :
                items.stream()
                        .map(oi -> OrderItemResponse.builder()
                                .itemId(oi.getItemId())
                                .barcode(oi.getBarcode())
                                .itemName(oi.getItemName())
                                .batchId(oi.getBatchId())
                                .qty(oi.getQty())
                                .unitPrice(oi.getUnitPrice())
                                .discountType(oi.getDiscountType() != null ? oi.getDiscountType().toString() : null)
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
                .customerName(customerName)
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
