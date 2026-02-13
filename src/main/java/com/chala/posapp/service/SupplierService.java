package com.chala.posapp.service;

import com.chala.posapp.dto.SupplierCreateRequest;
import com.chala.posapp.dto.SupplierQuickCreateRequest;
import com.chala.posapp.dto.SupplierResponse;
import com.chala.posapp.entity.Supplier;
import com.chala.posapp.entity.SupplierBankDetails;
import com.chala.posapp.entity.SupplierContact;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
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

        if (supplierRepository.existsByEmail(req.getEmail())){
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
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public SupplierResponse getSupplierById(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + id));
        return mapToResponse(supplier);
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
                .active(s.getActive())
                .bankDetails(bankDto)
                .contacts(contactDtos)
                .build();
    }
}
