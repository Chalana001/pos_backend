package com.chala.posapp.service;

import com.chala.posapp.dto.supplier.SupplierCreateRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentResponse;
import com.chala.posapp.dto.supplier.SupplierQuickCreateRequest;
import com.chala.posapp.dto.supplier.SupplierResponse;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.supplier.SupplierBankDetails;
import com.chala.posapp.entity.supplier.SupplierContact;
import com.chala.posapp.entity.supplier.SupplierPayment;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.SupplierPaymentRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final UserRepository userRepository;

    @Transactional
    public Supplier create(SupplierCreateRequest req) {

        Supplier supplier = new Supplier();
        supplier.setName(req.getName());
        supplier.setPhone(req.getPhone());
        supplier.setEmail(req.getEmail());
        supplier.setAddress(req.getAddress());
        supplier.setDueAmount(BigDecimal.ZERO);
        supplier.setActive(req.getActive() != null ? req.getActive() : true);

        if (req.getEmail() != null && !req.getEmail().isBlank() && supplierRepository.existsByEmail(req.getEmail())){
            throw new AlreadyExistsException("A supplier with email "+ req.getEmail()+ "already exists");
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

        return supplierRepository.save(supplier);
    }

    @Transactional
    public Supplier createQuickSupplier(SupplierQuickCreateRequest req) {

        Supplier s = new Supplier();
        s.setName(req.getName());
        s.setPhone(req.getPhone());
        s.setAddress(req.getAddress());
        s.setDueAmount(BigDecimal.ZERO);
        s.setActive(true);

        return supplierRepository.save(s);
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

        User user = getLoggedUser();
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

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }
}
