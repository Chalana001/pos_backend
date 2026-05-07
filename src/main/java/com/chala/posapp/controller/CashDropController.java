package com.chala.posapp.controller;

import com.chala.posapp.dto.CashDropSummaryResponse;
import com.chala.posapp.dto.CashDropResponse;
import com.chala.posapp.service.CashDropService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/cash-drops")
@RequiredArgsConstructor
public class CashDropController {

    private final CashDropService cashDropService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<Page<CashDropResponse>> getCashDrops(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) Long cashierUserId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CashDropResponse> result = cashDropService.getFilteredCashDrops(branchId, shiftId, cashierUserId, search, from, to, pageable);
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/summary")
    public ResponseEntity<CashDropSummaryResponse> getCashDropSummary(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) Long cashierUserId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        CashDropSummaryResponse result = cashDropService.getCashDropSummary(branchId, shiftId, cashierUserId, search, from, to);
        return ResponseEntity.ok(result);
    }
}
