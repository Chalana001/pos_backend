package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.CustomerNoteCreateRequest;
import com.chala.posapp.dto.CustomerNoteResponse;
import com.chala.posapp.dto.CustomerNoteUpdateRequest;
import com.chala.posapp.service.CustomerNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping
public class CustomerNotesController {

    private final CustomerNoteService service;

    // ✅ list notes (paged) - stable response
    @GetMapping("/customers/{customerId}/notes")
    public ResponseEntity<PageResponse<CustomerNoteResponse>> list(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<CustomerNoteResponse> result = service.list(customerId, pageable);

        PageResponse<CustomerNoteResponse> response = PageResponse.<CustomerNoteResponse>builder()
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

    @PostMapping("/customers/{customerId}/notes")
    public ResponseEntity<CustomerNoteResponse> create(
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerNoteCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(customerId, request));
    }

    @PutMapping("/customer-notes/{noteId}")
    public ResponseEntity<CustomerNoteResponse> update(
            @PathVariable Long noteId,
            @Valid @RequestBody CustomerNoteUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(noteId, request));
    }


    @DeleteMapping("/customer-notes/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable Long noteId) {
        service.delete(noteId);
        return ResponseEntity.noContent().build();
    }
}
