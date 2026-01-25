package com.chala.posapp.controller;

import com.chala.posapp.dto.*;
import com.chala.posapp.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
@Validated
public class ItemController {

    private final ItemService itemService;

    // ADMIN or MANAGER create items
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemCreateRequest request) {
        return ResponseEntity.ok(itemService.createItem(request));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ItemResponse>> bulkCreate(
            @RequestBody @NotEmpty(message = "List cannot be empty")
            List<@Valid ItemCreateRequest> requestList) { // @Valid දාන්නේ List එක ඇතුලේ ඒවට

        List<ItemResponse> responses = itemService.bulkCreate(requestList);
        return ResponseEntity.ok(responses);
    }


    @GetMapping("/with-stock")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<List<ItemWithStockResponse>> listWithStock(
            @RequestParam(required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.itemsWithStock(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(itemService.getItem(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<ItemResponse> getByBarcode(
            @PathVariable String barcode,
            @RequestParam(required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.getByBarcode(barcode, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/search")
    public ResponseEntity<List<ItemResponse>> search(
            @RequestParam String name,
            @RequestParam(required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.searchByName(name, branchId));
    }

    // /items?activeOnly=true
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<ItemResponse>> list(@RequestParam(required = false) Boolean activeOnly) {
        return ResponseEntity.ok(itemService.listAll(activeOnly));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.updateItem(id, request));
    }

    // safer: deactivate instead of delete
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable Long id) {
        itemService.deactivateItem(id);
        return ResponseEntity.ok("Item deactivated");
    }
}
