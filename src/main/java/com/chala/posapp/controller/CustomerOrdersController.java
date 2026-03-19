package com.chala.posapp.controller;

import com.chala.posapp.dto.customer.CustomerOrderListResponse;
import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping
public class CustomerOrdersController {

    private final OrderService service;

    @GetMapping("/customers/{customerId}/orders")
    public ResponseEntity<PageResponse<CustomerOrderListResponse>> list(
            @PathVariable (name = "customerId") Long customerId,
            @RequestParam(name = "orderType", defaultValue = "ALL") String orderType,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<CustomerOrderListResponse> result = service.list(customerId, orderType, pageable);

        PageResponse<CustomerOrderListResponse> response = PageResponse.<CustomerOrderListResponse>builder()
                .items(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .first(result.isFirst())
                .last(result.isLast())
                .build();

        return ResponseEntity.ok(response);
    }

}
