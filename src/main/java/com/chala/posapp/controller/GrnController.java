package com.chala.posapp.controller;

import com.chala.posapp.dto.GrnResponse;
import com.chala.posapp.service.GrnService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/grn") // URL එක වෙනස් කරන්න එපා, Frontend අවුල් යයි
@RequiredArgsConstructor
public class GrnController {

    private final GrnService grnService;

    @GetMapping
    public ResponseEntity<Page<GrnResponse>> getAll(
            @RequestParam(value = "search", defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(grnService.getGrns(search, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GrnResponse> getById(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(grnService.getGrnById(id));
    }
}