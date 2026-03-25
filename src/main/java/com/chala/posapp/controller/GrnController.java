package com.chala.posapp.controller;

import com.chala.posapp.dto.grn.GrnResponse;
import com.chala.posapp.service.GrnService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/grn") // URL එක වෙනස් කරන්න එපා, Frontend අවුල් යයි
@RequiredArgsConstructor
public class GrnController {

    private final GrnService grnService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<Page<GrnResponse>> getAll(
            @RequestParam(value = "search", defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(grnService.getGrns(search, page, size));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<GrnResponse> getById(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(grnService.getGrnById(id));
    }
}
