package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GrnService {

    private final GrnRepository grnRepository;
    private final GrnItemRepository grnItemRepository;
    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;

    // GRN අංක හදන Service එක (GRN-001, GRN-002...)
    private final GrnNumberService grnNumberService;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public GrnResponse createGrn(CreateGrnRequest request) {

        User user = getLoggedUser();

        // 1. Validate Branch & Supplier
        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        if (!branch.isActive()) throw new RuntimeException("Branch is inactive");

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new RuntimeException("Supplier not found"));

        // 2. Generate Unique GRN Number
        String grnNo = grnNumberService.generateGrnNo(branch.getId());

        // 3. Save GRN Header
        GRN grn = GRN.builder()
                .grnNo(grnNo)
                .supplier(supplier)
                .branch(branch)
                .totalAmount(request.getTotalAmount())
                .paidAmount(request.getPaidAmount() != null ? request.getPaidAmount() : java.math.BigDecimal.ZERO)
                .note(request.getNote())
                .receivedAt(LocalDateTime.now())
                .createdByUserId(user.getId())
                .build();

        GRN savedGrn = grnRepository.save(grn);

        List<GrnItem> grnItems = new ArrayList<>();
        List<GrnItemResponse> itemResponses = new ArrayList<>();

        int index = 0;
        // 4. Process Items (Create Batches) 🚀
        for (GrnItemRequest itemReq : request.getItems()) {
            index++;
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found: " + itemReq.getItemId()));

            // A. Update Item Master (Last Purchase Price)
            item.setCostPrice(itemReq.getCostPrice());
            item.setSellingPrice(itemReq.getSellingPrice());
            itemRepository.save(item);

            // ---------------------------------------------------------
            // 👇 FIX: Expiry Date Conversion Logic (Null Safe)
            // ---------------------------------------------------------
            LocalDateTime expiryDateTime = null;
            if (itemReq.getExpiryDate() != null) {
                // LocalDate එක LocalDateTime බවට හරවනවා (Time = 00:00:00)
                expiryDateTime = itemReq.getExpiryDate().atStartOfDay();
            }
            // ---------------------------------------------------------

            // B. Create NEW STOCK BATCH (With Supplier) ✅
            String uniqueBatchCode = String.format("GRN-%s-%d-%d", grnNo, item.getId(), index);

            StockBatch batch = StockBatch.builder()
                    .branch(branch)
                    .item(item)
                    .supplier(supplier)
                    .quantity(itemReq.getQty())
                    .originalQuantity(itemReq.getQty())
                    .costPrice(itemReq.getCostPrice())
                    .sellingPrice(itemReq.getSellingPrice())
                    .batchCode(uniqueBatchCode)
                    .receivedAt(LocalDateTime.now())
                    .expireDate(expiryDateTime) // 👈 මෙන්න මෙතනට අපි හදාගත්තු variable එක දානවා
                    .build();
            stockBatchRepository.save(batch);

            // C. Create GRN Item Record
            GrnItem grnItem = GrnItem.builder()
                    .grn(savedGrn)
                    .item(item)
                    .qty(itemReq.getQty())
                    .costPrice(itemReq.getCostPrice())
                    .sellingPrice(itemReq.getSellingPrice())
                    .amount(itemReq.getCostPrice().multiply(java.math.BigDecimal.valueOf(itemReq.getQty())))
                    .build();

            grnItems.add(grnItem);

            // Response Data
            itemResponses.add(GrnItemResponse.builder()
                    .itemId(item.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(itemReq.getQty())
                    .costPrice(itemReq.getCostPrice())
                    .sellingPrice(itemReq.getSellingPrice())
                    .lineTotal(grnItem.getAmount())
                    .build());
        }

        grnItemRepository.saveAll(grnItems);

        // 5. Build Final Response
        return GrnResponse.builder()
                .id(savedGrn.getId())
                .grnNo(savedGrn.getGrnNo())
                .supplierName(supplier.getName())
                .branchName(branch.getName())
                .totalAmount(savedGrn.getTotalAmount())
                .receivedAt(savedGrn.getReceivedAt())
                .note(savedGrn.getNote())
                .items(itemResponses)
                .build();
    }

    private GrnResponse mapToGrnResponse(GRN grn) {
        return GrnResponse.builder()
                .id(grn.getId())
                .grnNo(grn.getGrnNo())
                .supplierName(grn.getSupplier().getName())
                .branchName(grn.getBranch().getName())
                .totalAmount(grn.getTotalAmount())
                .receivedAt(grn.getReceivedAt())
                .note(grn.getNote()) // Invoice No එක තියෙන්නේ මෙතන
                // Items ටික List එකේ පෙන්නන්න ඕන නෑ, ඒ නිසා null යවමු හෝ හිස් List එකක්
                .items(new ArrayList<>())
                .build();
    }

    public Page<GrnResponse> getGrns(String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<GRN> grnPage = grnRepository.searchGrns(search, pageable);
        return grnPage.map(this::mapToGrnResponse);
    }

    public GrnResponse getGrnById(Long id) {
        // 1. ඉස්සෙල්ලා GRN Header (Parent) එක හොයාගන්නවා
        GRN grn = grnRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("GRN not found"));

        // 2. දැන් Items ටික වෙනම Query එකකින් ගන්නවා (Entity එකේ List නෑනේ) 🚀
        List<GrnItem> itemsList = grnItemRepository.findByGrnId(id);

        // 3. දැන් ඒ දෙක එකතු කරලා Response එක හදනවා
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

        // 4. Final Return
        return GrnResponse.builder()
                .id(grn.getId())
                .grnNo(grn.getGrnNo())
                .supplierName(grn.getSupplier().getName())
                .branchName(grn.getBranch().getName())
                .totalAmount(grn.getTotalAmount())
                .receivedAt(grn.getReceivedAt())
                .note(grn.getNote())
                .items(itemResponses) // ✅ අපි අතින් හොයාගත්තු List එක මෙතනට දානවා
                .build();
    }
}