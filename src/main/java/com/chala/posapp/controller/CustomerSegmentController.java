package com.chala.posapp.controller;

import com.chala.posapp.dto.promotion.CustomerSegmentDto;
import com.chala.posapp.service.CustomerSegmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer segments, the rules a promotion targets instead of a list of names.
 *
 * <p>Root-mounted like the other shop-facing controllers, and claimed by the {@code PROMOTIONS}
 * module: a segment exists to be targeted by one, so it is switched on and off with them.
 */
@RestController
@RequestMapping("/customer-segments")
@RequiredArgsConstructor
public class CustomerSegmentController {

    private final CustomerSegmentService segmentService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<CustomerSegmentDto>> list() {
        return ResponseEntity.ok(segmentService.list());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<CustomerSegmentDto> create(@Valid @RequestBody CustomerSegmentDto request) {
        return ResponseEntity.ok(segmentService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<CustomerSegmentDto> update(@PathVariable Long id, @Valid @RequestBody CustomerSegmentDto request) {
        return ResponseEntity.ok(segmentService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        segmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Rebuilds membership from current order history. Not run at the till. See the service. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{id}/recompute")
    public ResponseEntity<CustomerSegmentDto> recompute(@PathVariable Long id) {
        return ResponseEntity.ok(segmentService.recompute(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/recompute")
    public ResponseEntity<List<CustomerSegmentDto>> recomputeAll() {
        return ResponseEntity.ok(segmentService.recomputeAll());
    }
}
