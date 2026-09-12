package com.chala.posapp.service;

import com.chala.posapp.dto.CancelPurchaseRequest;
import com.chala.posapp.dto.CreatePurchaseRequest;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CashShiftRepository;
import com.chala.posapp.repository.GrnItemRepository;
import com.chala.posapp.repository.GrnRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.PurchaseReturnRepository;
import com.chala.posapp.repository.StockAdjustmentRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SupplierPaymentRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guards that decide whether a purchase can be voided — and therefore whether it can be
 * corrected by "Cancel &amp; Rebuild", which voids the original as part of the same
 * transaction.
 *
 * <p>These are all refusals, which is the point: voiding a bill gives back only what is
 * still OWED on it, so anything that has already moved — stock off the shelf, cash out of a
 * closed drawer, a payment already made to the supplier — cannot be undone by flipping a
 * status. Every one of these was a way to silently corrupt stock or the supplier ledger.
 */
class PurchaseSupersedeTest {

    private PurchaseRepository purchaseRepository;
    private StockBatchRepository stockBatchRepository;
    private SupplierPaymentRepository supplierPaymentRepository;
    private UserRepository userRepository;
    private CashShiftRepository cashShiftRepository;
    private SecurityUtils securityUtils;
    private PurchaseService service;

    private User admin;
    private Supplier supplier;
    private Branch branch;

    @BeforeEach
    void setUp() {
        purchaseRepository = mock(PurchaseRepository.class);
        stockBatchRepository = mock(StockBatchRepository.class);
        supplierPaymentRepository = mock(SupplierPaymentRepository.class);
        userRepository = mock(UserRepository.class);
        cashShiftRepository = mock(CashShiftRepository.class);
        securityUtils = mock(SecurityUtils.class);

        service = new PurchaseService(
                purchaseRepository,
                mock(GrnRepository.class),
                mock(GrnItemRepository.class),
                mock(ItemRepository.class),
                stockBatchRepository,
                mock(PurchaseReturnRepository.class),
                mock(BranchRepository.class),
                mock(SupplierRepository.class),
                mock(GrnNumberService.class),
                securityUtils,
                cashShiftRepository,
                mock(StockAdjustmentRepository.class),
                supplierPaymentRepository,
                userRepository,
                mock(ReportCacheInvalidator.class));

        admin = User.builder().id(1L).role(Role.ADMIN).build();
        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(securityUtils.isAdminLike(admin)).thenReturn(true);

        supplier = new Supplier();
        supplier.setId(7L);
        supplier.setName("Acme");
        supplier.setDueAmount(BigDecimal.valueOf(10_000));

        branch = new Branch();
        branch.setId(3L);
        branch.setName("Main");
    }

    /** A bill with one GRN whose stock is entirely untouched — the voidable base case. */
    private Purchase voidableBill() {
        GRN grn = GRN.builder().id(11L).grnNo("G-1").branch(branch).build();
        Purchase purchase = Purchase.builder()
                .id(42L)
                .invoiceNo("INV-8821")
                .supplier(supplier)
                .status(PurchaseStatus.COMPLETED)
                .cashSource(CashSource.NONE)
                .grandTotal(BigDecimal.valueOf(10_000))
                .dueAmount(BigDecimal.valueOf(10_000))
                .paidAmount(BigDecimal.ZERO)
                .grnList(new java.util.ArrayList<>(List.of(grn)))
                .build();
        grn.setPurchase(purchase);

        when(purchaseRepository.findById(42L)).thenReturn(Optional.of(purchase));
        when(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(anyLong(), anyString()))
                .thenReturn(List.of(untouchedBatch()));
        when(supplierPaymentRepository.existsByPurchaseId(42L)).thenReturn(false);
        return purchase;
    }

    private StockBatch untouchedBatch() {
        return StockBatch.builder().id(100L).quantity(5_000).originalQuantity(5_000).build();
    }

    @Test
    @DisplayName("a part-paid bill cannot be voided — cancelling reverses due, never payments")
    void refusesWhenSupplierPaymentsExist() {
        voidableBill();
        when(supplierPaymentRepository.existsByPurchaseId(42L)).thenReturn(true);

        // Why this matters, with numbers. A 10,000 bill part-paid 4,000 leaves due 6,000 and
        // supplier due 6,000. Voiding subtracts only the remaining 6,000, so a rebuild that
        // re-adds a 10,000 bill leaves the supplier owed 10,000 when the truth is 6,000 —
        // overstated by exactly what was already paid, with the payment row still hanging off
        // a cancelled bill. Refusing is the honest answer until payments can be carried over.
        assertThatThrownBy(() -> service.cancelPurchase(42L, cancelRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("supplier payments have been recorded against it");

        verify(purchaseRepository, never()).save(any());
        verify(stockBatchRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("a bill whose stock has moved cannot be voided")
    void refusesWhenStockHasMoved() {
        voidableBill();
        StockBatch partlySold = StockBatch.builder()
                .id(100L).quantity(3_000).originalQuantity(5_000).build();
        when(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(anyLong(), anyString()))
                .thenReturn(List.of(partlySold));

        assertThatThrownBy(() -> service.cancelPurchase(42L, cancelRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been sold or adjusted");

        verify(stockBatchRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("a drawer-paid bill cannot be voided once its shift has closed")
    void refusesWhenDrawerShiftClosed() {
        Purchase purchase = voidableBill();
        purchase.setCashSource(CashSource.CASH_DRAWER);
        purchase.setCashShiftId(55L);
        when(cashShiftRepository.findById(55L))
                .thenReturn(Optional.of(CashShift.builder().id(55L).status(ShiftStatus.CLOSED).build()));

        assertThatThrownBy(() -> service.cancelPurchase(42L, cancelRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("closed shift");

        verify(stockBatchRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("rebuild refuses BEFORE creating anything, so a blocked bill costs no re-typing")
    void replaceChecksGuardsBeforeCreating() {
        voidableBill();
        when(supplierPaymentRepository.existsByPurchaseId(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.replacePurchase(42L, new CreatePurchaseRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot rebuild this purchase because");

        // Guards run before anything is written, so an operator learns the bill cannot be
        // voided before re-entering forty lines rather than after.
        verify(purchaseRepository, never()).save(any());
        verify(stockBatchRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("the original is voided before the replacement is created, not after")
    void voidsOriginalBeforeCreatingReplacement() {
        Purchase purchase = voidableBill();

        // The replacement keeps the supplier's own invoice number, and V47's unique index
        // covers COMPLETED rows only — checked per statement, not at commit. Creating first
        // therefore means two COMPLETED rows share a number for an instant, and MySQL rejects
        // the insert with a duplicate key every single time. This ordering is load-bearing and
        // was a real 400 in the browser before it was fixed; it is not a style preference.
        //
        // Nothing is risked by voiding first: the method is one transaction, so a failure in
        // the create rolls the void back with it.
        //
        // The create is left to blow up on the empty request — by then the void has either
        // happened or it has not, which is the whole question.
        assertThatThrownBy(() -> service.replacePurchase(42L, new CreatePurchaseRequest()))
                .isInstanceOf(Exception.class);

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.CANCELED);
        verify(stockBatchRepository).deleteAll(any());
    }

    private CancelPurchaseRequest cancelRequest() {
        CancelPurchaseRequest request = new CancelPurchaseRequest();
        request.setReason("wrong prices");
        return request;
    }
}
