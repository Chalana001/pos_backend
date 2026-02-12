package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final GrnRepository grnRepository;
    private final GrnItemRepository grnItemRepository;
    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final GrnNumberService grnNumberService;

    @Transactional
    public PurchaseResponse createPurchase(CreatePurchaseRequest request) {

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));

        Purchase purchase = Purchase.builder()
                .supplier(supplier)
                .invoiceNo(request.getInvoiceNo())
                .createdAt(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO)
                .build();
        Purchase savedPurchase = purchaseRepository.save(purchase);

        BigDecimal grandTotal = BigDecimal.ZERO;

        List<GrnResponse> grnResponseList = new ArrayList<>();

        for (BranchPurchaseRequest branchReq : request.getBranches()) {

            Branch branch = branchRepository.findById(branchReq.getBranchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

            String grnNo = grnNumberService.generateGrnNo(branch.getId());

            GRN grn = GRN.builder()
                    .grnNo(grnNo)
                    .purchase(savedPurchase)
                    .supplier(supplier)
                    .branch(branch)
                    .receivedAt(LocalDateTime.now())
                    .totalAmount(BigDecimal.ZERO)
                    .note(request.getInvoiceNo())
                    .build();
            GRN savedGrn = grnRepository.save(grn);

            BigDecimal grnTotal = BigDecimal.ZERO;
            List<GrnItem> grnItems = new ArrayList<>();
            List<GrnItemResponse> itemResponses = new ArrayList<>();
            int index = 0;

            for (GrnItemRequest itemReq : branchReq.getItems()) {
                index++;
                Item item = itemRepository.findById(itemReq.getItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

                item.setCostPrice(itemReq.getCostPrice());
                item.setSellingPrice(itemReq.getSellingPrice());
                itemRepository.save(item);

                LocalDateTime expiry = (itemReq.getExpiryDate() != null) ? itemReq.getExpiryDate().atStartOfDay() : null;

                String batchCode = String.format("GRN-%s-%d-%d", grnNo, item.getId(), index);
                StockBatch batch = StockBatch.builder()
                        .branch(branch)
                        .item(item)
                        .supplier(supplier)
                        .quantity(itemReq.getQty())
                        .originalQuantity(itemReq.getQty())
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .batchCode(batchCode)
                        .receivedAt(LocalDateTime.now())
                        .expireDate(expiry)
                        .build();
                stockBatchRepository.save(batch);

                BigDecimal lineTotal = itemReq.getCostPrice().multiply(BigDecimal.valueOf(itemReq.getQty()));

                GrnItem grnItem = GrnItem.builder()
                        .grn(savedGrn)
                        .item(item)
                        .qty(itemReq.getQty())
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .amount(lineTotal)
                        .build();
                grnItems.add(grnItem);

                grnTotal = grnTotal.add(lineTotal);

                itemResponses.add(GrnItemResponse.builder()
                        .itemId(item.getId())
                        .itemName(item.getName())
                        .barcode(item.getBarcode())
                        .qty(itemReq.getQty())
                        .costPrice(itemReq.getCostPrice())
                        .sellingPrice(itemReq.getSellingPrice())
                        .lineTotal(lineTotal)
                        .build());
            }

            grnItemRepository.saveAll(grnItems);
            savedGrn.setTotalAmount(grnTotal);
            grnRepository.save(savedGrn);
            grandTotal = grandTotal.add(grnTotal);

            grnResponseList.add(GrnResponse.builder()
                    .id(savedGrn.getId())
                    .grnNo(savedGrn.getGrnNo())
                    .branchName(branch.getName())
                    .supplierName(supplier.getName())
                    .totalAmount(grnTotal)
                    .receivedAt(savedGrn.getReceivedAt())
                    .note(savedGrn.getNote())
                    .items(itemResponses)
                    .build());
        }

        savedPurchase.setGrandTotal(grandTotal);
        purchaseRepository.save(savedPurchase);

        return PurchaseResponse.builder()
                .purchaseId(savedPurchase.getId())
                .invoiceNo(savedPurchase.getInvoiceNo())
                .supplierName(supplier.getName())
                .grandTotal(savedPurchase.getGrandTotal())
                .createdAt(savedPurchase.getCreatedAt())
                .grnList(grnResponseList)
                .build();
    }

    public Page<PurchaseResponse> getAllPurchases(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<Purchase> purchasePage = purchaseRepository.findAllByOrderByIdDesc(pageable);

        return purchasePage.map(purchase -> PurchaseResponse.builder()
                .purchaseId(purchase.getId())
                .invoiceNo(purchase.getInvoiceNo())
                .supplierName(purchase.getSupplier().getName())
                .grandTotal(purchase.getGrandTotal())
                .createdAt(purchase.getCreatedAt())
                .grnList(null)
                .build());
    }

    public PurchaseResponse getPurchaseById(Long id) {

        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));

        List<GrnResponse> grnList = purchase.getGrnList().stream()
                .map(grn -> {

                    List<GrnItem> dbItems = grnItemRepository.findByGrnId(grn.getId());

                    List<GrnItemResponse> itemResponses = dbItems.stream()
                            .map(item -> GrnItemResponse.builder()
                                    .itemId(item.getItem().getId())
                                    .itemName(item.getItem().getName())
                                    .barcode(item.getItem().getBarcode())
                                    .qty(item.getQty())
                                    .costPrice(item.getCostPrice())
                                    .sellingPrice(item.getSellingPrice())
                                    .lineTotal(item.getAmount())
                                    .build())
                            .collect(Collectors.toList());

                    return GrnResponse.builder()
                            .id(grn.getId())
                            .grnNo(grn.getGrnNo())
                            .branchName(grn.getBranch().getName())
                            .totalAmount(grn.getTotalAmount())
                            .items(itemResponses)
                            .build();
                })
                .collect(Collectors.toList());

        return PurchaseResponse.builder()
                .purchaseId(purchase.getId())
                .invoiceNo(purchase.getInvoiceNo())
                .supplierName(purchase.getSupplier().getName())
                .grandTotal(purchase.getGrandTotal())
                .createdAt(purchase.getCreatedAt())
                .grnList(grnList)
                .build();
    }
}