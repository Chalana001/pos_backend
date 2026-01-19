package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerResponse create(CustomerCreateRequest request) {
        String phone = request.getPhone().trim();

        if (customerRepository.existsByPhone(phone))
            throw new RuntimeException("Customer phone already exists");

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
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        return map(c);
    }

    public CustomerResponse getByPhone(String phone) {
        Customer c = customerRepository.findByPhone(phone.trim())
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        return map(c);
    }

    public List<CustomerResponse> list(Boolean activeOnly) {
        return customerRepository.findAll().stream()
                .filter(c -> activeOnly == null || !activeOnly || c.isActive())
                .map(this::map)
                .toList();
    }

    public List<CustomerResponse> search(String name) {
        return customerRepository.findByNameContainingIgnoreCase(name.trim())
                .stream().map(this::map).toList();
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerUpdateRequest request) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (request.getName() != null) c.setName(request.getName().trim());
        if (request.getPhone() != null) c.setPhone(request.getPhone().trim());
        if (request.getAddress() != null) c.setAddress(request.getAddress());
        if (request.getCreditLimit() != null) c.setCreditLimit(request.getCreditLimit());
        if (request.getActive() != null) c.setActive(request.getActive());

        return map(c);
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
}
