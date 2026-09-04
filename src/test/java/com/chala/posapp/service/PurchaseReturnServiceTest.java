package com.chala.posapp.service;

import com.chala.posapp.dto.purchaseReturns.CreatePurchaseReturnRequest;
import com.chala.posapp.dto.purchaseReturns.PurchaseReturnResponse;
import com.chala.posapp.dto.purchaseReturns.ReturnGrnItemRequest;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseReturn;
import com.chala.posapp.entity.PurchaseReturnItem;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.GrnItemRepository;
import com.chala.posapp.repository.GrnRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.PurchaseReturnItemRepository;
import com.chala.posapp.repository.PurchaseReturnRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Purchase returns mix two quantity scales: the request (and the persisted
 * purchase_return_items.return_qty) are in DISPLAY units — the unit the GRN line
 * was entered in — while grn_items.qty and stock batches hold NORMALIZED base
 * units (10 pcs = 10000). These tests pin down the conversion at the boundary:
 * caps compare display against display, stock is deducted in base units, and
 * refunds are per primary unit regardless of the entry unit.
 */
class PurchaseReturnServiceTest {

    private PurchaseRepository purchaseRepository;
    private GrnRepository grnRepository;
    private GrnItemRepository grnItemRepository;
    private PurchaseReturnRepository purchaseReturnRepository;
    private PurchaseReturnItemRepository purchaseReturnItemRepository;
    private SecurityUtils securityUtils;
    private SupplierRepository supplierRepository;
    private StockBatchRepository stockBatchRepository;
    private UserRepository userRepository;

    private PurchaseReturnService service;

    private Supplier supplier;
    private Purchase purchase;
    private GRN grn;
    private User user;

    @BeforeEach
    void setUp() {
        purchaseRepository = mock(PurchaseRepository.class);
        grnRepository = mock(GrnRepository.class);
        grnItemRepository = mock(GrnItemRepository.class);
        purchaseReturnRepository = mock(PurchaseReturnRepository.class);
        purchaseReturnItemRepository = mock(PurchaseReturnItemRepository.class);
        securityUtils = mock(SecurityUtils.class);
        supplierRepository = mock(SupplierRepository.class);
        stockBatchRepository = mock(StockBatchRepository.class);
        userRepository = mock(UserRepository.class);
        ItemRepository itemRepository = mock(ItemRepository.class);
        ReportCacheInvalidator reportCacheInvalidator = mock(ReportCacheInvalidator.class);

        service = new PurchaseReturnService(
                purchaseRepository, grnRepository, grnItemRepository,
                purchaseReturnRepository, purchaseReturnItemRepository,
                securityUtils, supplierRepository, stockBatchRepository,
                itemRepository, userRepository, reportCacheInvalidator);

        supplier = new Supplier();
        supplier.setId(5L);
        supplier.setName("Test Supplier");
        supplier.setDueAmount(BigDecimal.ZERO);

        purchase = Purchase.builder()
                .id(42L)
                .invoiceNo("PUR-1")
                .supplier(supplier)
                .status(PurchaseStatus.COMPLETED)
                .dueAmount(BigDecimal.ZERO)
                .build();

        Branch branch = Branch.builder().id(7L).name("Main").build();
        grn = GRN.builder()
                .id(9L)
                .grnNo("GRN-0001")
                .purchase(purchase)
                .branch(branch)
                .build();

        user = User.builder().id(1L).username("admin").build();

        when(purchaseRepository.findById(42L)).thenReturn(Optional.of(purchase));
        when(grnRepository.findById(9L)).thenReturn(Optional.of(grn));
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(securityUtils.isAdminLike(user)).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(purchaseReturnRepository.countByPurchaseId(42L)).thenReturn(0L);
        when(purchaseReturnRepository.existsByDebitNoteNo(anyString())).thenReturn(false);
        when(purchaseReturnRepository.save(any(PurchaseReturn.class))).thenAnswer(inv -> {
            PurchaseReturn pr = inv.getArgument(0);
            pr.setId(100L);
            return pr;
        });
        when(purchaseReturnItemRepository.save(any(PurchaseReturnItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Item normalItem() {
        return Item.builder()
                .id(11L)
                .name("Soap")
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .build();
    }

    // 10 pcs @ Rs.100: display qty 10, normalized qty 10000, batch 10000 base units
    private GrnItem tenPieceLine(Item item) {
        return GrnItem.builder()
                .id(21L)
                .grn(grn)
                .item(item)
                .qty(10_000)
                .displayQty(BigDecimal.TEN)
                .qtyUnit(MeasurementUnit.PCS)
                .costPrice(new BigDecimal("100.00"))
                .amount(new BigDecimal("1000.00"))
                .build();
    }

    private StockBatch batchOf(Item item, int normalizedQty) {
        StockBatch batch = StockBatch.builder()
                .item(item)
                .quantity(normalizedQty)
                .originalQuantity(normalizedQty)
                .batchCode("GRN-GRN-0001-" + item.getId() + "-1")
                .build();
        when(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(anyLong(), anyString()))
                .thenReturn(List.of(batch));
        return batch;
    }

    private CreatePurchaseReturnRequest requestFor(Long grnItemId, int returnQty) {
        ReturnGrnItemRequest line = new ReturnGrnItemRequest();
        line.setGrnItemId(grnItemId);
        line.setReturnQty(returnQty);
        CreatePurchaseReturnRequest request = new CreatePurchaseReturnRequest();
        request.setGrnId(9L);
        request.setReason("Damaged");
        request.setItems(List.of(line));
        return request;
    }

    @Test
    @DisplayName("returning 10 pcs deducts 10000 base units from the batch, not 10")
    void pieceReturnDeductsNormalizedStock() {
        Item item = normalItem();
        GrnItem grnItem = tenPieceLine(item);
        when(grnItemRepository.findByGrnId(9L)).thenReturn(List.of(grnItem));
        when(purchaseReturnItemRepository.sumReturnedQtyByGrnItemId(21L)).thenReturn(0);
        StockBatch batch = batchOf(item, 10_000);

        PurchaseReturnResponse response = service.processReturn(42L, requestFor(21L, 10));

        assertThat(batch.getQuantity()).isZero();
        assertThat(response.getTotalReturnAmount()).isEqualByComparingTo("1000.00");
        // persisted return qty stays in display units, like the rows that already exist
        assertThat(response.getItems()).singleElement()
                .satisfies(i -> assertThat(i.getReturnQty()).isEqualTo(10));
    }

    @Test
    @DisplayName("the cap is display-scale: returning 11 of 10 purchased pcs is rejected")
    void overReturnRejectedAtDisplayScale() {
        Item item = normalItem();
        GrnItem grnItem = tenPieceLine(item);
        when(grnItemRepository.findByGrnId(9L)).thenReturn(List.of(grnItem));
        when(purchaseReturnItemRepository.sumReturnedQtyByGrnItemId(21L)).thenReturn(0);
        batchOf(item, 10_000);

        assertThatThrownBy(() -> service.processReturn(42L, requestFor(21L, 11)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeds returnable qty");
    }

    @Test
    @DisplayName("a second full return is rejected server-side, not just hidden by the UI")
    void repeatedFullReturnRejected() {
        Item item = normalItem();
        GrnItem grnItem = tenPieceLine(item);
        when(grnItemRepository.findByGrnId(9L)).thenReturn(List.of(grnItem));
        when(purchaseReturnItemRepository.sumReturnedQtyByGrnItemId(21L)).thenReturn(10);
        batchOf(item, 9_990);

        assertThatThrownBy(() -> service.processReturn(42L, requestFor(21L, 10)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been fully returned");
    }

    @Test
    @DisplayName("a line entered in grams refunds what was paid, not 1000x the per-kg cost")
    void gramEnteredLineRefundsPaidAmount() {
        Item item = Item.builder()
                .id(12L)
                .name("Sugar")
                .itemType(ItemType.WEIGHT)
                .defaultUnit(MeasurementUnit.KG)
                .build();
        // 500 g @ Rs.1000/kg: display qty 500 (G), normalized 500 grams, paid Rs.500
        GrnItem grnItem = GrnItem.builder()
                .id(22L)
                .grn(grn)
                .item(item)
                .qty(500)
                .displayQty(new BigDecimal("500"))
                .qtyUnit(MeasurementUnit.G)
                .costPrice(new BigDecimal("1000.00"))
                .amount(new BigDecimal("500.00"))
                .build();
        when(grnItemRepository.findByGrnId(9L)).thenReturn(List.of(grnItem));
        when(purchaseReturnItemRepository.sumReturnedQtyByGrnItemId(22L)).thenReturn(0);
        StockBatch batch = batchOf(item, 500);

        PurchaseReturnResponse response = service.processReturn(42L, requestFor(22L, 500));

        assertThat(batch.getQuantity()).isZero();
        assertThat(response.getTotalReturnAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("free (FOC) units are returnable and a full return refunds exactly the net paid")
    void freeUnitsReturnableAtDilutedCost() {
        Item item = normalItem();
        // 10 + 2 free @ Rs.1000 total: diluted cost 83.33/pc, batch holds 12000 base units
        GrnItem grnItem = GrnItem.builder()
                .id(23L)
                .grn(grn)
                .item(item)
                .qty(10_000)
                .displayQty(BigDecimal.TEN)
                .freeQty(2_000)
                .displayFreeQty(new BigDecimal("2"))
                .qtyUnit(MeasurementUnit.PCS)
                .costPrice(new BigDecimal("83.33"))
                .amount(new BigDecimal("1000.00"))
                .build();
        when(grnItemRepository.findByGrnId(9L)).thenReturn(List.of(grnItem));
        when(purchaseReturnItemRepository.sumReturnedQtyByGrnItemId(23L)).thenReturn(0);
        StockBatch batch = batchOf(item, 12_000);

        PurchaseReturnResponse response = service.processReturn(42L, requestFor(23L, 12));

        assertThat(batch.getQuantity()).isZero();
        // 12 x 83.33 — a rounding whisker under the Rs.1000 paid, never more
        assertThat(response.getTotalReturnAmount()).isEqualByComparingTo("999.96");

        Mockito.verify(purchaseReturnItemRepository)
                .save(Mockito.argThat(item23 -> item23.getReturnQty() == 12));
    }
}
