package com.chala.posapp.controller;

import com.chala.posapp.dto.*;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.dto.shift.CloseShiftRequest;
import com.chala.posapp.dto.shift.OpenShiftRequest;
import com.chala.posapp.dto.shift.ShiftResponse;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.service.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/open")
    public ResponseEntity<ShiftResponse> open(@Valid @RequestBody OpenShiftRequest request) {
        return ResponseEntity.ok(shiftService.openShift(request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/all")
    public ResponseEntity<Page<ShiftResponse>> getAllShifts(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "cashierId", required = false) Long cashierId,
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(name = "status", required = false) ShiftStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        Page<ShiftResponse> shifts = shiftService.getAllShifts(branchId, cashierId, startDate, endDate, status, page, size);
        return ResponseEntity.ok(shifts);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{shiftId}")
    public ResponseEntity<ShiftResponse> getShift(@PathVariable(name = "shiftId") Long shiftId) {
        return ResponseEntity.ok(shiftService.getShift(shiftId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{shiftId}/orders")
    public ResponseEntity<Page<OrderResponse>> getShiftOrders(
            @PathVariable(name = "shiftId") Long shiftId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(shiftService.getShiftOrders(shiftId, page, size));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{shiftId}/expenses")
    public ResponseEntity<Page<ExpenseResponse>> getShiftExpenses(
            @PathVariable(name = "shiftId") Long shiftId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(shiftService.getShiftExpenses(shiftId, page, size));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/me")
    public ResponseEntity<ShiftResponse> myShift() {
        return ResponseEntity.ok(shiftService.getMyCurrentShift());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/admin-current")
    public ResponseEntity<ShiftResponse> getAdminCurrentShift(@RequestParam(name = "branchId") Long branchId) {
        return ResponseEntity.ok(shiftService.getAdminShift(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/cashdrop")
    public ResponseEntity<ShiftResponse> cashDrop(@Valid @RequestBody CreateCashDropRequest request) {
        return ResponseEntity.ok(shiftService.addCashDrop(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/close")
    public ResponseEntity<ShiftResponse> close(@Valid @RequestBody CloseShiftRequest request) {
        return ResponseEntity.ok(shiftService.closeShift(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/active")
    public ResponseEntity<List<ShiftResponse>> activeShifts(@RequestParam(name = "branchId") Long branchId) {
        return ResponseEntity.ok(shiftService.getAllActiveShiftsByBranch(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/branch/open")
    public ResponseEntity<ShiftResponse> openByBranch(
            @RequestParam(name = "branchId") Long branchId,
            @Valid @RequestBody OpenShiftRequest request
    ) {
        return ResponseEntity.ok(shiftService.openShiftByBranch(branchId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{shiftId}/close")
    public ResponseEntity<ShiftResponse> closeById(
            @PathVariable(name = "shiftId") Long shiftId,
            @Valid @RequestBody CloseShiftRequest request
    ) {
        return ResponseEntity.ok(shiftService.closeShiftById(shiftId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{shiftId}/cashdrop")
    public ResponseEntity<ShiftResponse> cashDropById(
            @PathVariable(name = "shiftId") Long shiftId,
            @Valid @RequestBody CreateCashDropRequest request
    ) {
        return ResponseEntity.ok(shiftService.addCashDropByShiftId(shiftId, request));
    }
}
