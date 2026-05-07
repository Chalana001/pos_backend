package com.chala.posapp.service;

import com.chala.posapp.dto.customer.CustomerCreateRequest;
import com.chala.posapp.dto.customer.CustomerPaymentRequest;
import com.chala.posapp.dto.customer.CustomerResponse;
import com.chala.posapp.dto.customer.CustomerUpdateRequest;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public CustomerResponse create(CustomerCreateRequest request) {
        String phone = request.getPhone().trim();

        if (customerRepository.existsByPhone(phone))
            throw new AlreadyExistsException("Customer phone already exists");

        Customer c = Customer.builder()
                .name(request.getName().trim())
                .phone(phone)
                .address(request.getAddress())
                .creditLimit(request.getCreditLimit())
                .dueAmount(0)
                .active(true)
                .build();

        return map(customerRepository.save(c));
    }

    public CustomerResponse get(Long id) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        return map(c);
    }

    public CustomerResponse getByPhone(String phone) {
        Customer c = customerRepository.findByPhone(phone.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        return map(c);
    }

    public List<CustomerResponse> list(Boolean activeOnly) {
        return customerRepository.findAll(Sort.by("createdAt").descending())
                .stream()
                .filter(c -> activeOnly == null || !activeOnly || c.isActive())
                .map(this::map)
                .toList();
    }

    public Page<CustomerResponse> listPage(String search, Boolean activeOnly, Boolean active, String from, String to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return customerRepository.searchCustomers(
                        search != null ? search.trim() : "",
                        activeOnly,
                        active,
                        parseStartOfDay(from),
                        parseEndOfDay(to),
                        pageable
                )
                .map(this::map);
    }

    public List<CustomerResponse> search(String name) {
        return customerRepository.findByNameContainingIgnoreCase(name.trim())
                .stream().map(this::map).toList();
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerUpdateRequest request) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        if (request.getName() != null) c.setName(request.getName().trim());
        if (request.getPhone() != null) c.setPhone(request.getPhone().trim());
        if (request.getAddress() != null) c.setAddress(request.getAddress());
        if (request.getCreditLimit() != null) {
            if (request.getCreditLimit() < c.getDueAmount()) {
                throw new BadRequestException("Credit limit cannot be lower than current due amount");
            }
            c.setCreditLimit(request.getCreditLimit());
        }
        if (request.getActive() != null) c.setActive(request.getActive());

        return map(c);
    }

    @Transactional
    public CustomerResponse recordPayment(Long customerId, CustomerPaymentRequest request) {

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with ID: " + customerId));

        double paymentAmount = request.getAmount();

        if (paymentAmount > customer.getDueAmount()) {
            throw new BadRequestException("Payment amount exceeds the total due amount: " + customer.getDueAmount());
        }

        customer.setDueAmount(customer.getDueAmount() - paymentAmount);
        customerRepository.save(customer);

        List<Order> pendingOrders = orderRepository.findPendingCreditOrders(customerId);
        double remainingPayment = paymentAmount;

        for (Order order : pendingOrders) {
            if (remainingPayment <= 0) break;

            double orderDue = order.getDueAmount();

            if (remainingPayment >= orderDue) {
                order.setPaidAmount(order.getPaidAmount() + orderDue);
                order.setDueAmount(0.0);
                order.setStatus(OrderStatus.COMPLETED);

                remainingPayment -= orderDue;
            } else {
                order.setPaidAmount(order.getPaidAmount() + remainingPayment);
                order.setDueAmount(orderDue - remainingPayment);
                // Status එක වෙනස් කරන්නේ නෑ, මොකද තව ණය ඉතුරුයි

                remainingPayment = 0; // සල්ලි ඉවරයි
            }
            orderRepository.save(order);
        }

        return map(customer);
    }

    private CustomerResponse map(Customer c) {
        return CustomerResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .phone(c.getPhone())
                .address(c.getAddress())
                .dueAmount(c.getDueAmount())
                .creditLimit(c.getCreditLimit())
                .active(c.isActive())
                .createdAt(c.getCreatedAt())
                .build();
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

}
