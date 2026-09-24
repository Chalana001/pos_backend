package com.chala.posapp.service;

import com.chala.posapp.dto.supplier.SupplierCreateRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentResponse;
import com.chala.posapp.dto.supplier.SupplierQuickCreateRequest;
import com.chala.posapp.dto.supplier.SupplierResponse;
import com.chala.posapp.dto.supplier.SupplierUpdateRequest;
import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.supplier.SupplierBankDetails;
import com.chala.posapp.entity.supplier.SupplierContact;
import com.chala.posapp.entity.supplier.SupplierPayment;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CashShiftRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.SupplierPaymentRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {
    private final SupplierRepository supplierRepository;
    private final PurchaseRepository purchaseRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SecurityUtils securityUtils;
    private final CashShiftRepository cashShiftRepository;

    @Transactional
    public SupplierResponse create(SupplierCreateRequest req) {

        String name = normalizeRequired(req.getName(), "Supplier name is required");
        String phone = normalizeOptional(req.getPhone());
        String email = normalizeOptional(req.getEmail());

        // Checked against tombstones too: a soft-deleted supplier keeps its number, and the
        // unique index still enforces it, so a live-only check would pass here and then fail
        // at the constraint with the generic "invalid or duplicate data" message.
        if (phone != null && supplierRepository.countByPhoneIncludingDeleted(phone) > 0) {
            throw new AlreadyExistsException(phoneTakenMessage(phone));
        }

        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setPhone(phone);
        supplier.setEmail(email);
        supplier.setAddress(normalizeOptional(req.getAddress()));
        supplier.setDueAmount(BigDecimal.ZERO);
        supplier.setActive(req.getActive() != null ? req.getActive() : true);

        if (email != null && supplierRepository.countByEmailIncludingDeleted(email) > 0){
            throw new AlreadyExistsException(emailTakenMessage(email));
        }

        if (req.getContacts() != null) {
            for (String c : req.getContacts()) {
                SupplierContact sc = new SupplierContact();
                sc.setContactNumber(c);
                sc.setSupplier(supplier);
                supplier.getContacts().add(sc);
            }
        }

        if (req.getBank() != null) {
            SupplierBankDetails bd = new SupplierBankDetails();
            bd.setBankName(req.getBank().getBankName());
            bd.setAccountNumber(req.getBank().getAccountNumber());
            bd.setAccountName(req.getBank().getAccountName());
            bd.setBranchName(req.getBank().getBranchName());
            bd.setSupplier(supplier);
            supplier.setBankDetails(bd);
        }

        return mapToResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse createQuickSupplier(SupplierQuickCreateRequest req) {

        String name = normalizeRequired(req.getName(), "Supplier name is required");
        String phone = normalizeOptional(req.getPhone());

        // The quick-add modal has always labelled this field optional and sent "" when it
        // was left blank; that empty string was the reason a second numberless supplier
        // could never be created. It normalises to NULL now.
        if (phone != null && supplierRepository.countByPhoneIncludingDeleted(phone) > 0) {
            throw new AlreadyExistsException(phoneTakenMessage(phone));
        }

        Supplier s = new Supplier();
        s.setName(name);
        s.setPhone(phone);
        s.setAddress(normalizeOptional(req.getAddress()));
        s.setDueAmount(BigDecimal.ZERO);
        s.setActive(true);

        return mapToResponse(supplierRepository.save(s));
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> getAllActiveSuppliers() {
        List<Supplier> suppliers = supplierRepository.findByActiveTrue();

        return suppliers.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplierById(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + id));
        return mapToResponse(supplier);
    }

    @Transactional
    public SupplierResponse recordPayment(Long supplierId, SupplierPaymentRequest request) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + supplierId));

        BigDecimal paymentAmount = normalizeMoney(request.getAmount());
        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Payment amount must be greater than zero");
        }

        BigDecimal currentDue = normalizeMoney(supplier.getDueAmount());
        if (paymentAmount.compareTo(currentDue) > 0) {
            throw new BadRequestException("Payment amount exceeds the supplier due amount: " + currentDue);
        }

        User user = securityUtils.getCurrentUser();
        CashSource cashSource = resolveCashSource(request.getCashSource(), paymentAmount);
        CashShift drawerShift = applyDrawerCashOutIfNeeded(cashSource, paymentAmount, user, request);
        BigDecimal remainingPayment = paymentAmount;
        List<SupplierPayment> payments = new ArrayList<>();
        if (request.getPurchaseId() != null) {
            Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase not found with id: " + request.getPurchaseId()));
            if (!supplierId.equals(purchase.getSupplier().getId())) {
                throw new BadRequestException("Purchase does not belong to this supplier");
            }
            if (purchase.getStatus() == PurchaseStatus.CANCELED) {
                throw new BadRequestException("Cannot record payment against a canceled purchase");
            }

            BigDecimal purchaseDue = normalizeMoney(purchase.getDueAmount());
            BigDecimal allocated = remainingPayment.min(purchaseDue);
            if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                purchase.setPaidAmount(normalizeMoney(purchase.getPaidAmount()).add(allocated));
                purchase.setDueAmount(purchaseDue.subtract(allocated));
                purchaseRepository.save(purchase);
                payments.add(buildPayment(supplier, purchase, allocated, request, user));
                remainingPayment = remainingPayment.subtract(allocated);
            }
        }

        List<Purchase> pendingPurchases = purchaseRepository
                .findBySupplierIdAndStatusAndDueAmountGreaterThanOrderByCreatedAtAscIdAsc(
                        supplierId,
                        PurchaseStatus.COMPLETED,
                        BigDecimal.ZERO
                );

        for (Purchase purchase : pendingPurchases) {
            if (remainingPayment.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            if (request.getPurchaseId() != null && request.getPurchaseId().equals(purchase.getId())) {
                continue;
            }

            BigDecimal purchaseDue = normalizeMoney(purchase.getDueAmount());
            if (purchaseDue.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal allocated = remainingPayment.min(purchaseDue);
            purchase.setPaidAmount(normalizeMoney(purchase.getPaidAmount()).add(allocated));
            purchase.setDueAmount(purchaseDue.subtract(allocated));
            purchaseRepository.save(purchase);

            payments.add(buildPayment(supplier, purchase, allocated, request, user));
            remainingPayment = remainingPayment.subtract(allocated);
        }

        if (remainingPayment.compareTo(BigDecimal.ZERO) > 0) {
            payments.add(buildPayment(supplier, null, remainingPayment, request, user));
        }

        for (SupplierPayment payment : payments) {
            payment.setCashSource(cashSource);
            if (drawerShift != null) {
                payment.setCashShiftId(drawerShift.getId());
                payment.setCashierUserId(user == null ? null : user.getId());
                payment.setCashSourceBranchId(drawerShift.getBranchId());
            }
        }

        supplier.setDueAmount(currentDue.subtract(paymentAmount));
        supplierRepository.save(supplier);
        supplierPaymentRepository.saveAll(payments);

        return mapToResponse(supplier);
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse> paymentHistory(Long supplierId) {
        if (!supplierRepository.existsById(supplierId)) {
            throw new ResourceNotFoundException("Supplier not found with id: " + supplierId);
        }

        return supplierPaymentRepository.findBySupplierIdOrderByPaidAtDesc(supplierId).stream()
                .map(this::mapPayment)
                .toList();
    }

    /**
     * Edit the fields a shop actually knows about a supplier: name, phone number,
     * address, email and bank details.
     *
     * A null field means "leave it alone", so a partial body is safe. dueAmount is
     * deliberately NOT settable here: it is the payables ledger, moved only by receiving
     * stock and by recording a payment, and a form that could write it would let one
     * typo quietly erase a debt.
     */
    @Transactional
    public SupplierResponse update(Long id, SupplierUpdateRequest req) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + id));

        if (req.getName() != null) {
            supplier.setName(normalizeRequired(req.getName(), "Supplier name is required"));
        }

        if (req.getPhone() != null) {
            String phone = normalizeOptional(req.getPhone());
            // Keeping the same number is not a clash with itself.
            if (phone != null && !phone.equals(supplier.getPhone())
                    && supplierRepository.countByPhoneIncludingDeleted(phone) > 0) {
                throw new AlreadyExistsException(phoneTakenMessage(phone));
            }
            supplier.setPhone(phone);
        }

        if (req.getEmail() != null) {
            String email = normalizeOptional(req.getEmail());
            if (email != null && !email.equals(supplier.getEmail())
                    && supplierRepository.countByEmailIncludingDeleted(email) > 0) {
                throw new AlreadyExistsException(emailTakenMessage(email));
            }
            supplier.setEmail(email);
        }

        if (req.getAddress() != null) {
            supplier.setAddress(normalizeOptional(req.getAddress()));
        }

        if (req.getActive() != null) {
            supplier.setActive(req.getActive());
        }

        applyBankDetails(supplier, req.getBank());

        return mapToResponse(supplierRepository.save(supplier));
    }

    /**
     * Soft delete. The row stays, keeping its purchase and payment history and its phone
     * number, and simply stops appearing: Supplier carries
     * @SQLRestriction("deleted_at IS NULL"), so it drops out of every JPA query without a
     * single finder having to be changed.
     *
     * Refused while money is still owed. A supplier with an outstanding payable is a debt
     * the shop has to settle, and hiding it would make the balance disappear from the
     * directory while the ledger kept it -- the worst of both.
     */
    @Transactional
    public void delete(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + id));

        BigDecimal due = normalizeMoney(supplier.getDueAmount());
        if (due.compareTo(BigDecimal.ZERO) > 0) {
            throw new ConflictException("This supplier still has an outstanding payable of " + due
                    + ". Settle it before deleting the supplier.");
        }

        if (purchaseRepository.existsBySupplierId(id)) {
            throw new ConflictException("This supplier has purchase history and cannot be deleted. You can mark it as Inactive instead.");
        }

        supplier.setActive(false);
        supplier.setDeletedAt(LocalDateTime.now());
        supplierRepository.save(supplier);
    }

    /**
     * Blank is not a value here.
     *
     * Storing "" in an optional *unique* column is exactly what capped numberless
     * suppliers at one: '' is a value, so the unique index tolerated a single row
     * holding it. NULL is what "not given" has to mean.
     */
    private String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    /**
     * Say which kind of clash this is. "Already exists" is misleading when the supplier
     * the operator is being told about appears nowhere in the directory, because it is a
     * tombstone -- and a tombstone keeps its phone number, so the unique index still
     * refuses it.
     */
    private String phoneTakenMessage(String phone) {
        return supplierRepository.existsByPhone(phone)
                ? "Another supplier already uses the phone number " + phone
                : "A deleted supplier still holds the phone number " + phone
                        + ". Use a different number, or restore that supplier instead.";
    }

    private String emailTakenMessage(String email) {
        return supplierRepository.existsByEmail(email)
                ? "Another supplier already uses the email address " + email
                : "A deleted supplier still holds the email address " + email
                        + ". Use a different address, or restore that supplier instead.";
    }

    /**
     * Upsert or clear the bank details attached to a supplier.
     *
     * A null bank object means the caller said nothing about banking, so the existing row
     * is left alone. An object whose every field is blank is an operator clearing the
     * section, which removes the child row through the entity's orphanRemoval.
     */
    private void applyBankDetails(Supplier supplier, SupplierUpdateRequest.Bank bank) {
        if (bank == null) {
            return;
        }

        String bankName = normalizeOptional(bank.getBankName());
        String accountNumber = normalizeOptional(bank.getAccountNumber());
        String accountName = normalizeOptional(bank.getAccountName());
        String branchName = normalizeOptional(bank.getBranchName());

        boolean anyValue = bankName != null || accountNumber != null
                || accountName != null || branchName != null;

        if (!anyValue) {
            supplier.setBankDetails(null);
            return;
        }

        SupplierBankDetails details = supplier.getBankDetails();
        if (details == null) {
            details = new SupplierBankDetails();
            details.setSupplier(supplier);
            supplier.setBankDetails(details);
        }
        details.setBankName(bankName);
        details.setAccountNumber(accountNumber);
        details.setAccountName(accountName);
        details.setBranchName(branchName);
    }

    private SupplierResponse mapToResponse(Supplier s) {

        SupplierResponse.BankDetailsDto bankDto = null;
        if (s.getBankDetails() != null) {
            bankDto = SupplierResponse.BankDetailsDto.builder()
                    .bankName(s.getBankDetails().getBankName())
                    .branchName(s.getBankDetails().getBranchName())
                    .accountNumber(s.getBankDetails().getAccountNumber())
                    .accountName(s.getBankDetails().getAccountName())
                    .build();
        }

        List<SupplierResponse.ContactDto> contactDtos = s.getContacts().stream()
                .map(contact -> SupplierResponse.ContactDto.builder()
                        .build())
                .collect(Collectors.toList());

        return SupplierResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .phone(s.getPhone())
                .email(s.getEmail())
                .address(s.getAddress())
                .dueAmount(normalizeMoney(s.getDueAmount()))
                .active(s.getActive())
                .bankDetails(bankDto)
                .contacts(contactDtos)
                .build();
    }

    private SupplierPayment buildPayment(
            Supplier supplier,
            Purchase purchase,
            BigDecimal amount,
            SupplierPaymentRequest request,
            User user
    ) {
        return SupplierPayment.builder()
                .supplier(supplier)
                .purchase(purchase)
                .amount(normalizeMoney(amount))
                .paymentMethod(normalizePaymentMethod(request.getPaymentMethod()))
                .note(request.getNote() == null ? null : request.getNote().trim())
                .paidAt(LocalDateTime.now())
                .createdByUserId(user == null ? null : user.getId())
                .build();
    }

    private SupplierPaymentResponse mapPayment(SupplierPayment payment) {
        Purchase purchase = payment.getPurchase();
        Supplier supplier = payment.getSupplier();
        return SupplierPaymentResponse.builder()
                .id(payment.getId())
                .supplierId(supplier.getId())
                .supplierName(supplier.getName())
                .purchaseId(purchase == null ? null : purchase.getId())
                .invoiceNo(purchase == null ? null : purchase.getInvoiceNo())
                .amount(normalizeMoney(payment.getAmount()))
                .paymentMethod(payment.getPaymentMethod())
                .cashSource(payment.getCashSource())
                .cashShiftId(payment.getCashShiftId())
                .cashierUserId(payment.getCashierUserId())
                .cashSourceBranchId(payment.getCashSourceBranchId())
                .note(payment.getNote())
                .paidAt(payment.getPaidAt())
                .build();
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CashSource resolveCashSource(CashSource requestedCashSource, BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return CashSource.NONE;
        }
        return requestedCashSource == null || requestedCashSource == CashSource.NONE
                ? CashSource.BRANCH_CASH
                : requestedCashSource;
    }

    private CashShift applyDrawerCashOutIfNeeded(CashSource cashSource, BigDecimal paymentAmount, User user, SupplierPaymentRequest request) {
        if (cashSource != CashSource.CASH_DRAWER || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Long branchId = resolveDrawerBranchId(user, request);

        CashShift shift = cashShiftRepository.findByBranchIdAndCashierUserIdAndStatus(branchId, user.getId(), ShiftStatus.OPEN)
                .orElseThrow(() -> new BadRequestException("An open shift is required when supplier payment comes from the cash drawer"));

        shift.setTotalExpenses(shift.getTotalExpenses() + paymentAmount.doubleValue());
        return cashShiftRepository.save(shift);
    }

    private Long resolveDrawerBranchId(User user, SupplierPaymentRequest request) {
        if (user == null) {
            throw new BadRequestException("User is required when supplier payment comes from the cash drawer");
        }
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.SUPER_ADMIN) {
            if (user.getBranchId() == null) {
                throw new BadRequestException("An assigned branch is required when supplier payment comes from the cash drawer");
            }
            return user.getBranchId();
        }

        if (request.getCashSourceBranchId() != null && request.getCashSourceBranchId() > 0) {
            return request.getCashSourceBranchId();
        }

        if (request.getPurchaseId() == null) {
            throw new BadRequestException("Drawer branch is required when supplier drawer payment is not linked to a purchase");
        }

        Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found with id: " + request.getPurchaseId()));
        List<Long> branchIds = purchase.getGrnList().stream()
                .map(GRN::getBranch)
                .map(branch -> branch.getId())
                .distinct()
                .toList();
        if (branchIds.size() == 1) {
            return branchIds.get(0);
        }
        throw new BadRequestException("Drawer branch is required when supplier drawer payment is linked to a multi-branch purchase");
    }

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser(). Use SecurityUtils instead
}
