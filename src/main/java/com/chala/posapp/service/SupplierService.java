package com.chala.posapp.service;

import com.chala.posapp.dto.SupplierCreateRequest;
import com.chala.posapp.dto.SupplierQuickCreateRequest;
import com.chala.posapp.dto.SupplierResponse;
import com.chala.posapp.entity.Supplier;
import com.chala.posapp.entity.SupplierBankDetails;
import com.chala.posapp.entity.SupplierContact;
import com.chala.posapp.repository.SupplierRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierRepository supplierRepository;

    @Transactional
    public Supplier create(SupplierCreateRequest req) {

        Supplier supplier = new Supplier();
        supplier.setName(req.getName());
        supplier.setPhone(req.getPhone());
        supplier.setEmail(req.getEmail());
        supplier.setAddress(req.getAddress());
        supplier.setActive(req.getActive() != null ? req.getActive() : true);

        // Contacts
        if (req.getContacts() != null) {
            for (String c : req.getContacts()) {
                SupplierContact sc = new SupplierContact();
                sc.setContactNumber(c);
                sc.setSupplier(supplier);
                supplier.getContacts().add(sc);
            }
        }

        // Bank details
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

    public Supplier createQuickSupplier(SupplierQuickCreateRequest req) {

        Supplier s = new Supplier();
        s.setName(req.getName());
        s.setPhone(req.getPhone());
        s.setAddress(req.getAddress());
        s.setActive(true);

        return supplierRepository.save(s);
    }

    public List<SupplierResponse> getAllActiveSuppliers() {
        List<Supplier> suppliers = supplierRepository.findByActiveTrue();

        return suppliers.stream()
                .map(this::mapToResponse) // පහළ තියෙන helper method එක call කරනවා
                .collect(Collectors.toList());
    }

    // --- GET BY ID ---
    public SupplierResponse getSupplierById(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Supplier not found with id: " + id));
        return mapToResponse(supplier);
    }

    // --- HELPER: Map Entity to DTO ---
    private SupplierResponse mapToResponse(Supplier s) {

        // 1. Map Bank Details (if exists)
        SupplierResponse.BankDetailsDto bankDto = null;
        if (s.getBankDetails() != null) {
            bankDto = SupplierResponse.BankDetailsDto.builder()
                    .bankName(s.getBankDetails().getBankName())
                    .branchName(s.getBankDetails().getBranchName())
                    .accountNumber(s.getBankDetails().getAccountNumber())
                    .accountName(s.getBankDetails().getAccountName())
                    .build();
        }

        // 2. Map Contacts (Example Logic - Adjust based on your Contact Entity)
        /* * Note: ඔයාගේ SupplierContact Entity එකේ fields මම හරියටම දන්නේ නෑ.
         * string එකක් නම් s.getContacts() කෙලින්ම යවන්න පුළුවන්.
         * Object එකක් නම් stream().map() දාන්න ඕන.
         */
        List<SupplierResponse.ContactDto> contactDtos = s.getContacts().stream()
                .map(contact -> SupplierResponse.ContactDto.builder()
                        // .contactName(contact.getSomeField()) // Uncomment & fix this
                        .build())
                .collect(Collectors.toList());

        // 3. Final Build
        return SupplierResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .phone(s.getPhone())
                .email(s.getEmail())
                .address(s.getAddress())
                .active(s.getActive())
                .bankDetails(bankDto)
                .contacts(contactDtos)
                .build();
    }
}
