package com.chala.posapp.service;

import com.chala.posapp.dto.ExpenseTypeDeleteResponse;
import com.chala.posapp.dto.ExpenseTypeRequest;
import com.chala.posapp.dto.ExpenseTypeResponse;
import com.chala.posapp.entity.ExpenseType;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ExpenseRepository;
import com.chala.posapp.repository.ExpenseTypeRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseTypeService {

    private static final List<String> DEFAULT_TYPES = List.of(
            "Tea",
            "Lunch",
            "Staff Meals",
            "Transport",
            "Fuel",
            "Delivery Charges",
            "Electricity Bill",
            "Water Bill",
            "Internet Bill",
            "Telephone Bill",
            "Rent",
            "Stationery",
            "Packaging Materials",
            "Cleaning Supplies",
            "Shop Maintenance",
            "Equipment Repairs",
            "Salary Advance",
            "Staff Welfare",
            "Marketing And Ads",
            "Donations",
            "Miscellaneous",
            "Other"
    );

    private final ExpenseTypeRepository expenseTypeRepository;
    private final ExpenseRepository expenseRepository;

    public List<ExpenseTypeResponse> listAll() {
        ensureDefaults();
        return expenseTypeRepository.findAllByOrderByNameAsc().stream()
                .map(this::map)
                .toList();
    }

    public List<ExpenseTypeResponse> listActive() {
        ensureDefaults();
        return expenseTypeRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public ExpenseTypeResponse create(ExpenseTypeRequest request) {
        ensureDefaults();
        String name = normalizeName(request.getName());
        expenseTypeRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            throw new AlreadyExistsException("Expense type already exists");
        });

        ExpenseType saved = expenseTypeRepository.save(ExpenseType.builder()
                .name(name)
                .countInProfitReport(request.isCountInProfitReport())
                .active(request.isActive())
                .build());
        return map(saved);
    }

    @Transactional
    public ExpenseTypeResponse update(Long id, ExpenseTypeRequest request) {
        ensureDefaults();
        ExpenseType type = getEntity(id);
        String name = normalizeName(request.getName());
        expenseTypeRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new AlreadyExistsException("Expense type already exists");
                });

        type.setName(name);
        type.setCountInProfitReport(request.isCountInProfitReport());
        type.setActive(request.isActive());
        return map(expenseTypeRepository.save(type));
    }

    @Transactional
    public ExpenseTypeDeleteResponse delete(Long id) {
        ensureDefaults();
        ExpenseType type = getEntity(id);
        long usageCount = expenseRepository.countByExpenseTypeId(id);
        if (usageCount > 0) {
            type.setActive(false);
            expenseTypeRepository.save(type);
            return ExpenseTypeDeleteResponse.builder()
                    .id(type.getId())
                    .name(type.getName())
                    .action("DEACTIVATED")
                    .build();
        }

        expenseTypeRepository.delete(type);
        return ExpenseTypeDeleteResponse.builder()
                .id(type.getId())
                .name(type.getName())
                .action("DELETED")
                .build();
    }

    private ExpenseType getEntity(Long id) {
        return expenseTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense type not found"));
    }

    private ExpenseTypeResponse map(ExpenseType type) {
        return ExpenseTypeResponse.builder()
                .id(type.getId())
                .name(type.getName())
                .countInProfitReport(type.isCountInProfitReport())
                .active(type.isActive())
                .usageCount(expenseRepository.countByExpenseTypeId(type.getId()))
                .createdAt(type.getCreatedAt())
                .build();
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new BadRequestException("Expense type name is required");
        }
        return normalized;
    }

    @Transactional
    protected void ensureDefaults() {
        if (!expenseTypeRepository.findAllByOrderByNameAsc().isEmpty()) {
            return;
        }

        String tenantId = TenantContext.getTenant();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }

        DEFAULT_TYPES.forEach(name -> {
            if (!expenseTypeRepository.existsByNameIgnoreCase(name.toLowerCase(Locale.ROOT))) {
                expenseTypeRepository.save(ExpenseType.builder()
                        .name(name)
                        .countInProfitReport(true)
                        .active(true)
                        .build());
            }
        });
    }
}
