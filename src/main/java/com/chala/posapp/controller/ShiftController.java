package com.chala.posapp.controller;

import com.chala.posapp.dto.*;
import com.chala.posapp.service.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    // cashier opens shift
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/open")
    public ResponseEntity<ShiftResponse> open(@Valid @RequestBody OpenShiftRequest request) {
        return ResponseEntity.ok(shiftService.openShift(request));
    }

    // current shift of logged user
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/me")
    public ResponseEntity<ShiftResponse> myShift() {
        return ResponseEntity.ok(shiftService.getMyCurrentShift());
    }

    // add expense
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/expense")
    public ResponseEntity<ShiftResponse> expense(@Valid @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.ok(shiftService.addExpense(request));
    }

    // add cash drop
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/cashdrop")
    public ResponseEntity<ShiftResponse> cashDrop(@Valid @RequestBody CreateCashDropRequest request) {
        return ResponseEntity.ok(shiftService.addCashDrop(request));
    }

    // close shift
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/close")
    public ResponseEntity<ShiftResponse> close(@Valid @RequestBody CloseShiftRequest request) {
        return ResponseEntity.ok(shiftService.closeShift(request));
    }
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/active")
    public ResponseEntity<ShiftResponse> activeShift(@RequestParam Long branchId) {
        return ResponseEntity.ok(shiftService.getActiveShiftByBranch(branchId));
    }
    // ✅ ADMIN/MANAGER open shift for selected branch
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/branch/open")
    public ResponseEntity<ShiftResponse> openByBranch(
            @RequestParam Long branchId,
            @Valid @RequestBody OpenShiftRequest request
    ) {
        return ResponseEntity.ok(shiftService.openShiftByBranch(branchId, request));
    }

    // ✅ ADMIN/MANAGER close by shift id
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{shiftId}/close")
    public ResponseEntity<ShiftResponse> closeById(
            @PathVariable Long shiftId,
            @Valid @RequestBody CloseShiftRequest request
    ) {
        return ResponseEntity.ok(shiftService.closeShiftById(shiftId, request));
    }

    // ✅ ADMIN/MANAGER add expense by shift id
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{shiftId}/expense")
    public ResponseEntity<ShiftResponse> expenseById(
            @PathVariable Long shiftId,
            @Valid @RequestBody CreateExpenseRequest request
    ) {
        return ResponseEntity.ok(shiftService.addExpenseByShiftId(shiftId, request));
    }

    // ✅ ADMIN/MANAGER add cashdrop by shift id
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{shiftId}/cashdrop")
    public ResponseEntity<ShiftResponse> cashDropById(
            @PathVariable Long shiftId,
            @Valid @RequestBody CreateCashDropRequest request
    ) {
        return ResponseEntity.ok(shiftService.addCashDropByShiftId(shiftId, request));
    }

}
