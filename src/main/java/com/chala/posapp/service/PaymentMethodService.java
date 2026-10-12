package com.chala.posapp.service;

import com.chala.posapp.dto.paymentmethod.PaymentMethodRequest;
import com.chala.posapp.dto.paymentmethod.PaymentMethodResponse;
import com.chala.posapp.entity.PaymentCategory;
import com.chala.posapp.entity.PaymentMethod;
import com.chala.posapp.entity.ReferencePolicy;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.PaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Authoritative source for payment methods. The checkout and settlement flows
 * resolve and validate a method here instead of trusting hardcoded lists, so a
 * newly configured or deactivated method takes effect without a code change.
 * Behavior (cash-like, reference required, credit) is read from the method's
 * flags, never from its display name.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentMethodService {

    private final PaymentMethodRepository paymentMethodRepository;

    /**
     * The methods the codebase already used as hardcoded strings, seeded so the
     * resolver works on a database that has not run the V63 data migration (the
     * H2 integration tests migrate schema only). Idempotent: existing codes are
     * skipped and admin edits are never overwritten.
     */
    private record DefaultMethod(String code, String name, PaymentCategory category,
            ReferencePolicy policy, String label, boolean cashLike, boolean credit,
            String icon, int order) {
    }

    private static final List<DefaultMethod> DEFAULT_METHODS = List.of(
            new DefaultMethod("CASH", "Cash", PaymentCategory.CASH, ReferencePolicy.NONE, null, true, false, "Banknote", 1),
            new DefaultMethod("CARD", "Card Payment", PaymentCategory.CARD, ReferencePolicy.OPTIONAL, "Approval Code", false, false, "CreditCard", 2),
            new DefaultMethod("BANK", "Bank Transfer", PaymentCategory.BANK_TRANSFER, ReferencePolicy.REQUIRED, "Bank Reference", false, false, "Landmark", 3),
            new DefaultMethod("CREDIT", "Credit", PaymentCategory.CREDIT, ReferencePolicy.NONE, null, false, true, "CheckCircle2", 4),
            new DefaultMethod("STORE_CREDIT", "Store Credit", PaymentCategory.CREDIT, ReferencePolicy.NONE, null, false, false, "Wallet", 5),
            new DefaultMethod("CHEQUE", "Cheque", PaymentCategory.BANK_TRANSFER, ReferencePolicy.OPTIONAL, "Cheque Number", false, false, "FileText", 6),
            new DefaultMethod("LEGACY", "Legacy", PaymentCategory.OTHER, ReferencePolicy.NONE, null, false, false, "Wallet", 99));

    @Transactional
    protected void ensureDefaults() {
        for (DefaultMethod def : DEFAULT_METHODS) {
            if (!paymentMethodRepository.existsByCodeIgnoreCase(def.code())) {
                // A fresh transient instance per insert: reusing one shared entity
                // across saves would merge (and stale-lock) after the first persist.
                paymentMethodRepository.save(PaymentMethod.builder()
                        .code(def.code()).displayName(def.name()).category(def.category())
                        .referencePolicy(def.policy()).referenceLabel(def.label())
                        .includeInExpectedCash(def.cashLike()).allowsCredit(def.credit())
                        .iconKey(def.icon()).active(true).system(true).sortOrder(def.order())
                        .build());
            }
        }
    }

    public List<PaymentMethodResponse> listAll() {
        ensureDefaults();
        return paymentMethodRepository.findAllByOrderBySortOrderAscDisplayNameAsc()
                .stream().map(this::map).toList();
    }

    public List<PaymentMethodResponse> listActive() {
        ensureDefaults();
        return paymentMethodRepository.findByActiveTrueOrderBySortOrderAscDisplayNameAsc()
                .stream().map(this::map).toList();
    }

    public PaymentMethodResponse getById(Long id) {
        return map(getEntity(id));
    }

    /** Resolve an active method by code for a NEW transaction, or throw. */
    public PaymentMethod resolveActiveForUse(String code) {
        PaymentMethod method = getByCode(code);
        if (!method.isActive()) {
            throw new BadRequestException("Payment method " + method.getDisplayName() + " is inactive");
        }
        return method;
    }

    /** Resolve a method by code regardless of active state (historical reads). */
    public PaymentMethod getByCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized.isBlank()) {
            throw new BadRequestException("Payment method is required");
        }
        return paymentMethodRepository.findByCodeIgnoreCase(normalized)
                .orElseGet(() -> {
                    // A code that predates seeding (or a fresh schema-only database)
                    // still resolves, so a historical or in-flight sale is never
                    // rejected for an unknown method.
                    ensureDefaults();
                    return paymentMethodRepository.findByCodeIgnoreCase(normalized)
                            .orElseThrow(() -> new BadRequestException(
                                    "Payment method " + normalized + " is not supported"));
                });
    }

    public boolean isCashLike(String code) {
        return getByCode(code).isIncludeInExpectedCash();
    }

    @Transactional
    public PaymentMethodResponse create(PaymentMethodRequest request) {
        String code = normalizeCode(request.getCode());
        if (code.isBlank()) {
            code = deriveCode(request.getDisplayName());
        }
        if (paymentMethodRepository.existsByCodeIgnoreCase(code)) {
            throw new AlreadyExistsException("A payment method with code " + code + " already exists");
        }
        String name = normalizeName(request.getDisplayName());
        if (paymentMethodRepository.existsByDisplayNameIgnoreCase(name)) {
            throw new AlreadyExistsException("A payment method named " + name + " already exists");
        }

        PaymentMethod saved = paymentMethodRepository.save(PaymentMethod.builder()
                .code(code)
                .displayName(name)
                .category(parseCategory(request.getCategory()))
                .referencePolicy(parsePolicy(request.getReferencePolicy()))
                .referenceLabel(normalizeLabel(request.getReferenceLabel()))
                .includeInExpectedCash(request.isIncludeInExpectedCash())
                .allowsCredit(request.isAllowsCredit())
                .iconKey(normalizeLabel(request.getIconKey()))
                .active(request.isActive())
                .system(false)
                .sortOrder(request.getSortOrder() == null ? nextSortOrder() : request.getSortOrder())
                .build());
        return map(saved);
    }

    @Transactional
    public PaymentMethodResponse update(Long id, PaymentMethodRequest request) {
        PaymentMethod method = getEntity(id);
        // code is immutable: it is the identifier stored on historical transactions.

        String name = normalizeName(request.getDisplayName());
        paymentMethodRepository.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .filter(m -> !m.getId().equals(id))
                .filter(m -> m.getDisplayName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(m -> {
                    throw new AlreadyExistsException("A payment method named " + name + " already exists");
                });

        method.setDisplayName(name);
        method.setCategory(parseCategory(request.getCategory()));
        method.setReferencePolicy(parsePolicy(request.getReferencePolicy()));
        method.setReferenceLabel(normalizeLabel(request.getReferenceLabel()));
        method.setIncludeInExpectedCash(request.isIncludeInExpectedCash());
        method.setAllowsCredit(request.isAllowsCredit());
        method.setIconKey(normalizeLabel(request.getIconKey()));
        method.setActive(request.isActive());
        if (request.getSortOrder() != null) {
            method.setSortOrder(request.getSortOrder());
        }
        return map(paymentMethodRepository.save(method));
    }

    @Transactional
    public PaymentMethodResponse setActive(Long id, boolean active) {
        PaymentMethod method = getEntity(id);
        method.setActive(active);
        return map(paymentMethodRepository.save(method));
    }

    private PaymentMethod getEntity(Long id) {
        return paymentMethodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method not found"));
    }

    private int nextSortOrder() {
        return paymentMethodRepository.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .mapToInt(PaymentMethod::getSortOrder).max().orElse(0) + 1;
    }

    private String deriveCode(String displayName) {
        String base = normalizeCode(displayName).replace(' ', '_');
        if (base.isBlank()) {
            throw new BadRequestException("A payment method code is required");
        }
        String candidate = base;
        int suffix = 2;
        while (paymentMethodRepository.existsByCodeIgnoreCase(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private PaymentCategory parseCategory(String value) {
        try {
            return PaymentCategory.valueOf(normalizeCode(value));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported payment category: " + value);
        }
    }

    private ReferencePolicy parsePolicy(String value) {
        try {
            return ReferencePolicy.valueOf(normalizeCode(value));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported reference policy: " + value);
        }
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new BadRequestException("Display name is required");
        }
        return normalized;
    }

    private String normalizeLabel(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PaymentMethodResponse map(PaymentMethod m) {
        return PaymentMethodResponse.builder()
                .id(m.getId())
                .code(m.getCode())
                .displayName(m.getDisplayName())
                .category(m.getCategory().name())
                .referencePolicy(m.getReferencePolicy().name())
                .referenceLabel(m.getReferenceLabel())
                .includeInExpectedCash(m.isIncludeInExpectedCash())
                .allowsCredit(m.isAllowsCredit())
                .iconKey(m.getIconKey())
                .active(m.isActive())
                .system(m.isSystem())
                .sortOrder(m.getSortOrder())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
