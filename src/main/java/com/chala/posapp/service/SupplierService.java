package com.chala.posapp.service;

import com.chala.posapp.dto.SupplierCreateRequest;
import com.chala.posapp.dto.SupplierQuickCreateRequest;
import com.chala.posapp.entity.Supplier;
import com.chala.posapp.entity.SupplierBankDetails;
import com.chala.posapp.entity.SupplierContact;
import com.chala.posapp.repository.SupplierRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

}
