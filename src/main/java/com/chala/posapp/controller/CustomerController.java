package com.chala.posapp.controller;

import com.chala.posapp.dto.customer.CustomerCreateRequest;
import com.chala.posapp.dto.customer.CustomerPaymentRequest;
import com.chala.posapp.dto.customer.CustomerResponse;
import com.chala.posapp.dto.customer.CustomerUpdateRequest;
import com.chala.posapp.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerCreateRequest request) {
        return ResponseEntity.ok(customerService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(customerService.get(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/phone/{phone}")
    public ResponseEntity<CustomerResponse> getByPhone(@PathVariable("phone") String phone) {
        return ResponseEntity.ok(customerService.getByPhone(phone));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<CustomerResponse>> list(
            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly
    ) {
        return ResponseEntity.ok(customerService.list(activeOnly));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/search")
    public ResponseEntity<List<CustomerResponse>> search(@RequestParam("name") String name) {
        return ResponseEntity.ok(customerService.search(name));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody CustomerUpdateRequest request
    ) {
        return ResponseEntity.ok(customerService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/{id}/payments")
    public ResponseEntity<CustomerResponse> recordPayment(
            @PathVariable("id") Long id,
            @Valid @RequestBody CustomerPaymentRequest request) {

        return ResponseEntity.ok(customerService.recordPayment(id, request));
    }
}
