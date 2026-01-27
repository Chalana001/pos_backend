package com.chala.posapp.service;

import com.chala.posapp.dto.GrnItemResponse;
import com.chala.posapp.dto.GrnResponse;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import com.chala.posapp.repository.GrnItemRepository;
import com.chala.posapp.repository.GrnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GrnService {

    private final GrnRepository grnRepository;
    private final GrnItemRepository grnItemRepository;

    // ❌ createGrn Method එක සම්පූර්ණයෙන්ම අයින් කරන්න.
    // ඒ Logic එක දැන් PurchaseService එකේ තියෙන්නේ.

    // ✅ 1. Search Logic (වෙනසක් නෑ, පරණ එකමයි)
    public Page<GrnResponse> getGrns(String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());

        // Repository එකේ searchGrns query එක තාම වැඩ කරනවා
        // මොකද අපි Note එකට Invoice No එක දාන නිසා.
        Page<GRN> grnPage = grnRepository.searchGrns(search, pageable);

        return grnPage.map(this::mapToGrnResponse);
    }

    // ✅ 2. Get Single GRN Logic (මේක ඕන View/Print කරන්න)
    public GrnResponse getGrnById(Long id) {
        GRN grn = grnRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("GRN not found"));

        // Items ටික ගන්නවා
        List<GrnItem> itemsList = grnItemRepository.findByGrnId(id);

        List<GrnItemResponse> itemResponses = itemsList.stream()
                .map(item -> GrnItemResponse.builder()
                        .itemId(item.getItem().getId())
                        .barcode(item.getItem().getBarcode())
                        .itemName(item.getItem().getName())
                        .qty(item.getQty())
                        .costPrice(item.getCostPrice())
                        .sellingPrice(item.getSellingPrice())
                        .lineTotal(item.getAmount())
                        .build())
                .collect(Collectors.toList());

        return GrnResponse.builder()
                .id(grn.getId())
                .grnNo(grn.getGrnNo())
                .supplierName(grn.getSupplier().getName())
                .branchName(grn.getBranch().getName())
                .totalAmount(grn.getTotalAmount())
                .receivedAt(grn.getReceivedAt())
                .note(grn.getNote()) // මෙතන Invoice No එක එනවා
                .items(itemResponses)
                .build();
    }

    // Helper Method
    private GrnResponse mapToGrnResponse(GRN grn) {
        return GrnResponse.builder()
                .id(grn.getId())
                .grnNo(grn.getGrnNo())
                .supplierName(grn.getSupplier().getName())
                .branchName(grn.getBranch().getName())
                .totalAmount(grn.getTotalAmount())
                .receivedAt(grn.getReceivedAt())
                .note(grn.getNote())
                .items(new ArrayList<>()) // List එකේදි Items යවන්නේ නෑ
                .build();
    }
}