package com.chala.posapp.service;

import com.chala.posapp.dto.supplier.SupplierCreateRequest;
import com.chala.posapp.dto.supplier.SupplierQuickCreateRequest;
import com.chala.posapp.dto.supplier.SupplierUpdateRequest;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.supplier.SupplierBankDetails;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CashShiftRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.SupplierPaymentRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A supplier could not be recorded without a phone number, yet the quick-add modal on the
 * purchase screens labelled the field "Optional" and sent "" for it. MySQL accepted exactly
 * one such row -- '' is a value, and the unique index tolerated one -- and refused every one
 * after it with a message about "invalid or duplicate data" that named no field.
 *
 * These pin the behaviour that replaced it: a blank means NULL, NULL is not a value the
 * unique index can only hold once, and the two kinds of clash are told apart in the message.
 */
class SupplierServiceTest {

    private SupplierRepository supplierRepository;
    private PurchaseRepository purchaseRepository;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        supplierRepository = mock(SupplierRepository.class);
        purchaseRepository = mock(PurchaseRepository.class);
        service = new SupplierService(
                supplierRepository,
                purchaseRepository,
                mock(SupplierPaymentRepository.class),
                mock(SecurityUtils.class),
                mock(CashShiftRepository.class)
        );
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(call -> call.getArgument(0));
    }

    private SupplierCreateRequest createRequest(String name, String phone) {
        SupplierCreateRequest request = new SupplierCreateRequest();
        request.setName(name);
        request.setPhone(phone);
        return request;
    }

    private Supplier existing(Long id, String name, String phone) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setName(name);
        supplier.setPhone(phone);
        supplier.setDueAmount(BigDecimal.ZERO);
        supplier.setActive(true);
        return supplier;
    }

    @Test
    @DisplayName("a supplier can be created with no phone number at all")
    void createWithoutPhone() {
        var response = service.create(createRequest("Perera Distributors", null));

        assertThat(response.getName()).isEqualTo("Perera Distributors");
        assertThat(response.getPhone()).isNull();
    }

    @Test
    @DisplayName("an empty-string phone is stored as NULL, not as ''")
    void blankPhoneBecomesNull() {
        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);

        service.create(createRequest("Silva Traders", "   "));

        verify(supplierRepository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isNull();
    }

    @Test
    @DisplayName("two suppliers may both go without a number. '' was a value the index allowed only once")
    void twoNumberlessSuppliers() {
        service.create(createRequest("First", ""));
        service.create(createRequest("Second", ""));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Supplier::getPhone).containsOnlyNulls();
    }

    @Test
    @DisplayName("the quick-add path the purchase screen uses normalises a blank the same way")
    void quickCreateNormalisesBlankPhone() {
        SupplierQuickCreateRequest request = new SupplierQuickCreateRequest();
        request.setName("Quick Supplier");
        request.setPhone("");

        assertThat(service.createQuickSupplier(request).getPhone()).isNull();
    }

    @Test
    @DisplayName("a duplicate phone number names the clash instead of returning a generic duplicate-data error")
    void duplicatePhone() {
        when(supplierRepository.existsByPhoneIncludingDeleted("0771234567")).thenReturn(true);
        when(supplierRepository.existsByPhone("0771234567")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("Copycat", "0771234567")))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("Another supplier already uses");
    }

    @Test
    @DisplayName("a number held only by a deleted supplier says so, rather than claiming a clash with nobody")
    void phoneHeldByTombstone() {
        when(supplierRepository.existsByPhoneIncludingDeleted("0771234567")).thenReturn(true);
        when(supplierRepository.existsByPhone("0771234567")).thenReturn(false);

        assertThatThrownBy(() -> service.create(createRequest("Copycat", "0771234567")))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("deleted supplier still holds");
    }

    @Test
    @DisplayName("a name is still required: it is the only thing that identifies the supplier")
    void blankNameRefused() {
        assertThatThrownBy(() -> service.create(createRequest("   ", "0771234567")))
                .isInstanceOf(BadRequestException.class);

        verify(supplierRepository, never()).save(any(Supplier.class));
    }

    @Test
    @DisplayName("edit sets the name, phone number and address")
    void updateFields() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing(5L, "Old Name", "0770000000")));

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setName("New Name");
        request.setPhone("0711111111");
        request.setAddress("12 Galle Road");

        var response = service.update(5L, request);

        assertThat(response.getName()).isEqualTo("New Name");
        assertThat(response.getPhone()).isEqualTo("0711111111");
        assertThat(response.getAddress()).isEqualTo("12 Galle Road");
    }

    @Test
    @DisplayName("editing a phone number to blank removes it rather than storing ''")
    void updateClearsPhone() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing(5L, "Supplier", "0770000000")));

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setPhone("   ");

        assertThat(service.update(5L, request).getPhone()).isNull();
    }

    @Test
    @DisplayName("a supplier keeping its own number is not a clash with itself")
    void updateKeepsOwnPhone() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing(5L, "Supplier", "0771111111")));
        when(supplierRepository.existsByPhoneIncludingDeleted("0771111111")).thenReturn(true);

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setPhone("0771111111");

        assertThat(service.update(5L, request).getPhone()).isEqualTo("0771111111");
    }

    @Test
    @DisplayName("moving to a number another supplier holds is refused")
    void updateRejectsTakenPhone() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing(5L, "Supplier", "0771111111")));
        when(supplierRepository.existsByPhoneIncludingDeleted("0779999999")).thenReturn(true);

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setPhone("0779999999");

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @DisplayName("editing contact details never touches the payables balance")
    void updateLeavesDueAmountAlone() {
        Supplier supplier = existing(5L, "Supplier", "0771111111");
        supplier.setDueAmount(BigDecimal.valueOf(4200));
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier));

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setName("Renamed");

        assertThat(service.update(5L, request).getDueAmount()).isEqualByComparingTo("4200");
        assertThat(supplier.getDueAmount()).isEqualByComparingTo("4200");
    }

    @Test
    @DisplayName("clearing every bank field removes the bank details row")
    void updateClearsBankDetails() {
        Supplier supplier = existing(5L, "Supplier", null);
        SupplierBankDetails details = new SupplierBankDetails();
        details.setBankName("BOC");
        details.setSupplier(supplier);
        supplier.setBankDetails(details);
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier));

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setBank(new SupplierUpdateRequest.Bank());

        assertThat(service.update(5L, request).getBankDetails()).isNull();
    }

    @Test
    @DisplayName("editing a supplier that no longer exists is a not-found, not a silent no-op")
    void updateMissing() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new SupplierUpdateRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a supplier still owed money cannot be deleted")
    void deleteRefusedWhileOwed() {
        Supplier supplier = existing(5L, "Supplier", "0771111111");
        supplier.setDueAmount(BigDecimal.valueOf(1500));
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("outstanding payable");

        assertThat(supplier.getDeletedAt()).isNull();
        assertThat(supplier.getActive()).isTrue();
    }

    @Test
    @DisplayName("a supplier with existing purchases cannot be deleted to protect invoice history")
    void deleteRefusedWhenPurchasesExist() {
        Supplier supplier = existing(5L, "Supplier", "0771111111");
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier));
        when(purchaseRepository.existsBySupplierId(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("purchase history");

        assertThat(supplier.getDeletedAt()).isNull();
        assertThat(supplier.getActive()).isTrue();
    }

    @Test
    @DisplayName("a supplier with zero balance and no purchases is soft-deleted")
    void deleteSoftDeletes() {
        Supplier supplier = existing(5L, "Supplier", "0771111111");
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier));

        service.delete(5L);

        assertThat(supplier.getDeletedAt()).isNotNull();
        assertThat(supplier.getActive()).isFalse();
    }
}
