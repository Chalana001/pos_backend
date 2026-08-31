package com.chala.posapp.service;

import com.chala.posapp.dto.saas.LifecycleDtos.DiscountRequest;
import com.chala.posapp.entity.DiscountCode;
import com.chala.posapp.entity.DiscountRedemption;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.DiscountCodeRepository;
import com.chala.posapp.repository.DiscountRedemptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Discount codes applied at onboarding, renewal or a plan change.
 *
 * <p>{@link #preview} answers "what would this code do" without consuming it, so the panel can
 * show the discounted figure while the operator is still filling in the form. {@link #redeem}
 * is the only thing that increments usage, and it is called from inside the same transaction
 * as the billing record it discounts — so a failed renewal never burns a single-use code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountCodeRepository codeRepository;
    private final DiscountRedemptionRepository redemptionRepository;

    /**
     * @param amountOff  what comes off, already clamped so it can never exceed the gross
     * @param reason     why the code was refused; null when it applies
     */
    public record DiscountPreview(
            boolean valid,
            String code,
            String description,
            String discountType,
            double value,
            double grossAmount,
            double amountOff,
            double netAmount,
            String reason
    ) {
        static DiscountPreview rejected(String code, double gross, String reason) {
            return new DiscountPreview(false, code, null, null, 0, gross, 0, gross, reason);
        }
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<DiscountCode> list() {
        return codeRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public DiscountCode get(Long id) {
        return codeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Discount code not found"));
    }

    @Transactional(readOnly = true)
    public List<DiscountRedemption> redemptions(Long codeId) {
        return redemptionRepository.findByCodeIdOrderByRedeemedAtDesc(codeId);
    }

    @Transactional
    public DiscountCode create(DiscountRequest request) {
        String code = normalise(request.code());
        if (codeRepository.existsByCodeIgnoreCase(code)) {
            throw new AlreadyExistsException("A discount code named " + code + " already exists");
        }
        DiscountCode entity = new DiscountCode();
        apply(entity, request, code);
        // usedCount and createdBy are never taken from the request — a client must not be
        // able to pre-age a code or attribute it to somebody else.
        entity.setUsedCount(0);
        entity.setCreatedBy(currentActor());
        return codeRepository.save(entity);
    }

    @Transactional
    public DiscountCode update(Long id, DiscountRequest request) {
        DiscountCode existing = get(id);
        String code = normalise(request.code());
        codeRepository.findByCodeIgnoreCase(code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new AlreadyExistsException("A discount code named " + code + " already exists");
                });
        apply(existing, request, code);
        return codeRepository.save(existing);
    }

    /** Copies the request onto the entity and validates the result. */
    private void apply(DiscountCode entity, DiscountRequest request, String code) {
        entity.setCode(code);
        entity.setDescription(trimToNull(request.description()));
        entity.setDiscountType(DiscountCode.DiscountType.valueOf(
                request.discountType().trim().toUpperCase(Locale.ROOT)));
        entity.setValue(request.value() == null ? 0 : request.value());
        entity.setValidFrom(request.validFrom());
        entity.setValidUntil(request.validUntil());
        entity.setMaxUses(request.maxUses());
        entity.setAppliesToPlanIds(trimToNull(request.appliesToPlanIds()));
        entity.setActive(request.active() == null || request.active());
        validate(entity);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public void delete(Long id) {
        DiscountCode code = get(id);
        if (code.getUsedCount() > 0) {
            // Deleting would take the redemption history with it via the cascade, and that
            // history is what explains a discounted line in the ledger.
            throw new BadRequestException(
                    "This code has been used " + code.getUsedCount() + " time(s). Switch it off instead of deleting it.");
        }
        codeRepository.delete(code);
    }

    private void validate(DiscountCode code) {
        if (code.getDiscountType() == null) {
            throw new BadRequestException("Discount type is required");
        }
        if (code.getValue() <= 0) {
            throw new BadRequestException("Discount value must be greater than zero");
        }
        if (code.getDiscountType() == DiscountCode.DiscountType.PERCENT && code.getValue() > 100) {
            throw new BadRequestException("A percentage discount cannot be more than 100%");
        }
        if (code.getValidFrom() != null && code.getValidUntil() != null
                && code.getValidUntil().isBefore(code.getValidFrom())) {
            throw new BadRequestException("The end date cannot be before the start date");
        }
        if (code.getMaxUses() != null && code.getMaxUses() < 1) {
            throw new BadRequestException("Maximum uses must be at least 1");
        }
    }

    // -------------------------------------------------------------- applying

    /** Works out what a code would do, without consuming it. */
    @Transactional(readOnly = true)
    public DiscountPreview preview(String rawCode, double gross, Long planId) {
        if (rawCode == null || rawCode.isBlank()) {
            return DiscountPreview.rejected(null, gross, "No code given.");
        }
        String code = normalise(rawCode);

        Optional<DiscountCode> found = codeRepository.findByCodeIgnoreCase(code);
        if (found.isEmpty()) {
            return DiscountPreview.rejected(code, gross, "No such discount code.");
        }
        DiscountCode discount = found.get();

        String rejection = discount.rejectionReason();
        if (rejection != null) {
            return DiscountPreview.rejected(code, gross, rejection);
        }
        if (!discount.appliesToPlan(planId)) {
            return DiscountPreview.rejected(code, gross, "This code does not apply to the selected plan.");
        }

        double off = discount.amountOff(gross);
        return new DiscountPreview(true, discount.getCode(), discount.getDescription(),
                discount.getDiscountType().name(), discount.getValue(),
                round(gross), round(off), round(gross - off), null);
    }

    /**
     * Consumes a code. Call inside the transaction that writes the payment, so the usage
     * counter and the billing record commit or roll back together.
     *
     * @return the preview that was applied, so the caller can record gross/discount/net
     */
    @Transactional
    public DiscountPreview redeem(String rawCode, double gross, Long planId, String tenantId, Long billingRecordId) {
        DiscountPreview preview = preview(rawCode, gross, planId);
        if (!preview.valid()) {
            throw new BadRequestException(preview.reason());
        }

        DiscountCode discount = codeRepository.findByCodeIgnoreCase(preview.code())
                .orElseThrow(() -> new ResourceNotFoundException("Discount code not found"));

        codeRepository.incrementUsage(discount.getId());
        redemptionRepository.save(DiscountRedemption.builder()
                .codeId(discount.getId())
                .code(discount.getCode())
                .tenantId(tenantId)
                .billingRecordId(billingRecordId)
                .grossAmount(preview.grossAmount())
                .amountOff(preview.amountOff())
                .netAmount(preview.netAmount())
                .redeemedBy(currentActor())
                .build());

        log.info("Discount {} redeemed by {} — {} off {}", discount.getCode(), tenantId,
                preview.amountOff(), preview.grossAmount());
        return preview;
    }

    @Transactional(readOnly = true)
    public double totalDiscountGiven() {
        return round(redemptionRepository.totalDiscountGiven());
    }

    private String normalise(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Discount code is required");
        }
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    /** Convenience for callers that just want "is this usable right now". */
    public boolean isUsable(String rawCode, Long planId) {
        if (rawCode == null || rawCode.isBlank()) {
            return false;
        }
        return codeRepository.findByCodeIgnoreCase(normalise(rawCode))
                .filter(DiscountCode::isUsable)
                .filter(code -> code.appliesToPlan(planId))
                .isPresent();
    }

    /** Exposed so the lifecycle service can stamp the expiry moment consistently. */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
