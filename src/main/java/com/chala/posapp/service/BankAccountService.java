package com.chala.posapp.service;

import com.chala.posapp.dto.BankAccountDeleteResponse;
import com.chala.posapp.dto.BankAccountRequest;
import com.chala.posapp.dto.BankAccountResponse;
import com.chala.posapp.entity.BankAccount;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BankAccountRepository;
import com.chala.posapp.repository.CashDropRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manageable "Bank Accounts" reference list — deliberately the same shape as
 * ExpenseTypeService (name + active flag, deactivate-if-in-use instead of a
 * hard delete once it's actually been used on a cash drop).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final CashDropRepository cashDropRepository;

    public List<BankAccountResponse> listAll() {
        return bankAccountRepository.findAllByOrderByNameAsc().stream()
                .map(this::map)
                .toList();
    }

    public List<BankAccountResponse> listActive() {
        return bankAccountRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::map)
                .toList();
    }

    public BankAccountResponse getById(Long id) {
        return map(getEntity(id));
    }

    @Transactional
    public BankAccountResponse create(BankAccountRequest request) {
        String name = normalizeName(request.getName());
        bankAccountRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            throw new AlreadyExistsException("A bank account with this name already exists");
        });

        BankAccount saved = bankAccountRepository.save(BankAccount.builder()
                .name(name)
                .accountNumber(blankToNull(request.getAccountNumber()))
                .bankName(blankToNull(request.getBankName()))
                .active(request.isActive())
                .build());
        return map(saved);
    }

    @Transactional
    public BankAccountResponse update(Long id, BankAccountRequest request) {
        BankAccount account = getEntity(id);
        String name = normalizeName(request.getName());
        bankAccountRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new AlreadyExistsException("A bank account with this name already exists");
                });

        account.setName(name);
        account.setAccountNumber(blankToNull(request.getAccountNumber()));
        account.setBankName(blankToNull(request.getBankName()));
        account.setActive(request.isActive());
        return map(bankAccountRepository.save(account));
    }

    @Transactional
    public BankAccountDeleteResponse delete(Long id) {
        BankAccount account = getEntity(id);
        long usageCount = cashDropRepository.countByBankAccountId(id);
        if (usageCount > 0) {
            // Cash drops already reference this account — deactivating (not
            // deleting) keeps that history intact and just hides it from the
            // "pick a bank account" dropdown going forward.
            account.setActive(false);
            bankAccountRepository.save(account);
            return BankAccountDeleteResponse.builder()
                    .id(account.getId())
                    .name(account.getName())
                    .action("DEACTIVATED")
                    .build();
        }

        bankAccountRepository.delete(account);
        return BankAccountDeleteResponse.builder()
                .id(account.getId())
                .name(account.getName())
                .action("DELETED")
                .build();
    }

    private BankAccount getEntity(Long id) {
        return bankAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
    }

    private BankAccountResponse map(BankAccount account) {
        return BankAccountResponse.builder()
                .id(account.getId())
                .name(account.getName())
                .accountNumber(account.getAccountNumber())
                .bankName(account.getBankName())
                .active(account.isActive())
                .usageCount(cashDropRepository.countByBankAccountId(account.getId()))
                .createdAt(account.getCreatedAt())
                .build();
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new BadRequestException("Bank account name is required");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
