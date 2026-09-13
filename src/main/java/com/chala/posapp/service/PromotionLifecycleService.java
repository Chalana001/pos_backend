package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.PromotionAuditDto;
import com.chala.posapp.dto.promotion.PromotionSettingsDto;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionAudit;
import com.chala.posapp.entity.PromotionAudit.Action;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionSettings;
import com.chala.posapp.entity.PromotionStatus;
import com.chala.posapp.entity.PromotionTarget;
import com.chala.posapp.entity.PromotionTier;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.PromotionAuditRepository;
import com.chala.posapp.repository.PromotionSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The stored half of a promotion's lifecycle: draft, pending approval, active, paused.
 *
 * <p>Whether an active promotion is live right now is not this class's business, the engine
 * checks dates and the gate checks counters on every sale. This is only the part that needs a
 * person: writing it, sending it for a second pair of eyes, switching it on and off. Every
 * transition writes an audit row, and {@code active} is kept in step with {@code status} here
 * so nothing else has to know the mapping.
 */
@Service
@RequiredArgsConstructor
public class PromotionLifecycleService {

    private final PromotionSettingsRepository settingsRepository;
    private final PromotionAuditRepository auditRepository;
    private final PromotionSnapshotCache snapshotCache;

    // ── settings ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PromotionSettings settings() {
        return settingsRepository.findFirstByOrderByIdAsc().orElseGet(PromotionSettings::defaults);
    }

    @Transactional
    public PromotionSettingsDto updateSettings(PromotionSettingsDto dto, User user) {
        if (dto.getApprovalThresholdPercent() != null
                && (dto.getApprovalThresholdPercent().signum() < 0 || dto.getApprovalThresholdPercent().doubleValue() > 100)) {
            throw new BadRequestException("Approval threshold must be between 0 and 100 percent");
        }
        if (dto.getApprovalThresholdAmount() != null && dto.getApprovalThresholdAmount().signum() < 0) {
            throw new BadRequestException("Approval threshold amount cannot be negative");
        }
        if (dto.isApprovalRequired() && dto.getApprovalThresholdPercent() == null && dto.getApprovalThresholdAmount() == null) {
            throw new BadRequestException("Set a percent or amount threshold, or switch approval off");
        }
        PromotionSettings settings = settingsRepository.findFirstByOrderByIdAsc().orElseGet(PromotionSettings::defaults);
        settings.setApprovalRequired(dto.isApprovalRequired());
        settings.setApprovalThresholdPercent(dto.getApprovalThresholdPercent());
        settings.setApprovalThresholdAmount(dto.getApprovalThresholdAmount());
        settings.setCashierManualStacking(dto.isCashierManualStacking());
        settings.setUpdatedBy(user == null ? null : user.getId());
        settings.setUpdatedAt(LocalDateTime.now());
        return toDto(settingsRepository.save(settings));
    }

    public PromotionSettingsDto toDto(PromotionSettings settings) {
        return PromotionSettingsDto.builder()
                .approvalRequired(settings.isApprovalRequired())
                .approvalThresholdPercent(settings.getApprovalThresholdPercent())
                .approvalThresholdAmount(settings.getApprovalThresholdAmount())
                .cashierManualStacking(settings.isCashierManualStacking())
                .build();
    }

    // ── approval rule ───────────────────────────────────────────────────────────────────

    /**
     * Whether this promotion's terms cross the shop's approval threshold.
     *
     * @param sellingPrice resolves an item's list price, for per-item offer prices and fixed
     *                     prices; may return null when unknown
     */
    public boolean approvalNeeded(Promotion promotion, Function<Long, BigDecimal> sellingPrice) {
        PromotionSettings settings = settings();
        if (!settings.isApprovalRequired()) {
            return false;
        }
        BigDecimal deepest = deepestDiscountPercent(promotion, sellingPrice);
        if (settings.getApprovalThresholdPercent() != null && deepest != null
                && deepest.compareTo(settings.getApprovalThresholdPercent()) >= 0) {
            return true;
        }
        BigDecimal amount = largestFlatAmount(promotion);
        return settings.getApprovalThresholdAmount() != null && amount != null
                && amount.compareTo(settings.getApprovalThresholdAmount()) >= 0;
    }

    /**
     * The deepest percentage cut this promotion can make, or null when it cannot be told
     * without prices the caller could not supply. Conservative where the mechanic is a rate.
     */
    public BigDecimal deepestDiscountPercent(Promotion promotion, Function<Long, BigDecimal> sellingPrice) {
        BigDecimal deepest = null;
        PromotionEffectType effect = promotion.getEffectType() == null ? PromotionEffectType.DISCOUNT : promotion.getEffectType();
        switch (effect) {
            case DISCOUNT -> {
                if (promotion.getDiscountType() == DiscountType.PERCENT) {
                    deepest = promotion.getDiscountValue();
                }
            }
            case FIXED_PRICE -> deepest = maxPercentOffFor(promotion, promotion.getDiscountValue(), sellingPrice);
            case BUY_X_GET_Y_FREE -> {
                BigDecimal buy = nz(promotion.getBuyQty());
                BigDecimal get = nz(promotion.getGetQty());
                if (buy.add(get).signum() > 0) {
                    deepest = get.multiply(BigDecimal.valueOf(100)).divide(buy.add(get), 2, RoundingMode.HALF_UP);
                }
            }
            case TIERED -> deepest = promotion.getTiers().stream()
                    .filter(t -> t.getDiscountType() == DiscountType.PERCENT && t.getDiscountValue() != null)
                    .map(PromotionTier::getDiscountValue)
                    .max(Comparator.naturalOrder()).orElse(null);
            case CHEAPEST_FREE -> {
                BigDecimal buy = nz(promotion.getBuyQty());
                if (buy.signum() > 0) {
                    deepest = BigDecimal.valueOf(100).divide(buy, 2, RoundingMode.HALF_UP);
                }
            }
            case BUNDLE -> { }
        }
        // A per-item offer price or rate on a target can cut deeper than the promotion's own terms.
        for (PromotionTarget target : promotion.getTargets()) {
            BigDecimal pct = null;
            if (target.getOfferPrice() != null && target.getItemId() != null) {
                BigDecimal price = sellingPrice == null ? null : sellingPrice.apply(target.getItemId());
                pct = percentOff(price, target.getOfferPrice());
            } else if (target.getDiscountType() == DiscountType.PERCENT && target.getDiscountValue() != null) {
                pct = target.getDiscountValue();
            }
            if (pct != null && (deepest == null || pct.compareTo(deepest) > 0)) {
                deepest = pct;
            }
        }
        return deepest;
    }

    private BigDecimal maxPercentOffFor(Promotion promotion, BigDecimal fixedPrice, Function<Long, BigDecimal> sellingPrice) {
        if (fixedPrice == null || sellingPrice == null) {
            return null;
        }
        BigDecimal deepest = null;
        for (PromotionTarget target : promotion.getTargets()) {
            if (target.getItemId() == null) {
                continue;
            }
            BigDecimal pct = percentOff(sellingPrice.apply(target.getItemId()), fixedPrice);
            if (pct != null && (deepest == null || pct.compareTo(deepest) > 0)) {
                deepest = pct;
            }
        }
        return deepest;
    }

    private static BigDecimal percentOff(BigDecimal listPrice, BigDecimal offerPrice) {
        if (listPrice == null || listPrice.signum() <= 0 || offerPrice == null) {
            return null;
        }
        return listPrice.subtract(offerPrice).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100)).divide(listPrice, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal largestFlatAmount(Promotion promotion) {
        BigDecimal largest = null;
        if (promotion.getDiscountType() == DiscountType.FIXED
                && (promotion.getEffectType() == null || promotion.getEffectType() == PromotionEffectType.DISCOUNT)) {
            largest = promotion.getDiscountValue();
        }
        for (PromotionTier tier : promotion.getTiers()) {
            if (tier.getDiscountType() == DiscountType.FIXED && tier.getDiscountValue() != null
                    && (largest == null || tier.getDiscountValue().compareTo(largest) > 0)) {
                largest = tier.getDiscountValue();
            }
        }
        for (PromotionTarget target : promotion.getTargets()) {
            if (target.getDiscountType() == DiscountType.FIXED && target.getDiscountValue() != null
                    && (largest == null || target.getDiscountValue().compareTo(largest) > 0)) {
                largest = target.getDiscountValue();
            }
        }
        return largest;
    }

    /**
     * The terms that matter for approval, as one string. Editing a name or a date does not
     * change it; editing the discount, the mechanic or the price list does, and knocks an
     * approved promotion back to pending.
     */
    public String termsFingerprint(Promotion promotion) {
        String targets = promotion.getTargets().stream()
                .map(t -> t.getItemId() + "/" + t.getCategoryId() + "/" + t.getSubCategoryId() + "/" + t.getCustomerId()
                        + "@" + t.getOfferPrice() + "/" + t.getDiscountType() + "/" + t.getDiscountValue())
                .sorted().collect(Collectors.joining(","));
        String tiers = promotion.getTiers().stream()
                .map(t -> t.getMinQty() + "/" + t.getMinAmount() + "/" + t.getDiscountType() + "/" + t.getDiscountValue())
                .sorted().collect(Collectors.joining(","));
        return String.join("|",
                String.valueOf(promotion.getScope()), String.valueOf(promotion.getEffectType()),
                String.valueOf(promotion.getDiscountType()), String.valueOf(promotion.getDiscountValue()),
                String.valueOf(promotion.getBuyQty()), String.valueOf(promotion.getGetQty()),
                String.valueOf(promotion.getMaxDiscountAmount()), String.valueOf(promotion.isAllowBelowCost()),
                targets, tiers);
    }

    // ── transitions ─────────────────────────────────────────────────────────────────────

    /** A new promotion: a draft, or, if the client asked for it on, straight through submission. */
    public void initialise(Promotion promotion, boolean wantsActive, User user, Function<Long, BigDecimal> sellingPrice) {
        promotion.setCreatedBy(user == null ? null : user.getId());
        promotion.setUpdatedBy(user == null ? null : user.getId());
        if (!wantsActive) {
            place(promotion, PromotionStatus.DRAFT);
            return;
        }
        submitInternal(promotion, user, sellingPrice);
    }

    /**
     * After an edit. A change to the terms of an approved promotion sends it back for approval;
     * a change to its name or dates does not. The client's active flag is honoured as a
     * pause/resume/submit request.
     */
    public void onUpdated(Promotion promotion, boolean termsChanged, boolean wantsActive, User user,
                          Function<Long, BigDecimal> sellingPrice) {
        promotion.setUpdatedBy(user == null ? null : user.getId());
        if (termsChanged && promotion.getApprovedBy() != null) {
            promotion.setApprovedBy(null);
            promotion.setApprovedAt(null);
            promotion.setApprovalNote(null);
        }
        switch (promotion.getStatus()) {
            case DRAFT -> {
                if (wantsActive) {
                    submitInternal(promotion, user, sellingPrice);
                }
            }
            case PENDING_APPROVAL -> {
                // Terms changed or not, it stays pending; the approver sees the current version.
            }
            case ACTIVE -> {
                if (!wantsActive) {
                    place(promotion, PromotionStatus.PAUSED);
                    audit(promotion, Action.PAUSED, user, null);
                } else if (termsChanged && approvalNeeded(promotion, sellingPrice)) {
                    promotion.setSubmittedBy(user == null ? null : user.getId());
                    place(promotion, PromotionStatus.PENDING_APPROVAL);
                    audit(promotion, Action.SUBMITTED, user, "Terms changed; needs approval again");
                }
            }
            case PAUSED -> {
                if (wantsActive) {
                    submitInternal(promotion, user, sellingPrice);
                }
            }
        }
        audit(promotion, Action.UPDATED, user, null);
    }

    @Transactional
    public void submit(Promotion promotion, User user, Function<Long, BigDecimal> sellingPrice) {
        if (promotion.getStatus() != PromotionStatus.DRAFT && promotion.getStatus() != PromotionStatus.PAUSED) {
            throw new BadRequestException("Only a draft or paused promotion can be submitted");
        }
        submitInternal(promotion, user, sellingPrice);
    }

    /** The second pair of eyes. Must be an admin, and not the person who submitted it. */
    @Transactional
    public void approve(Promotion promotion, User user, String note) {
        if (promotion.getStatus() != PromotionStatus.PENDING_APPROVAL) {
            throw new BadRequestException("This promotion is not waiting for approval");
        }
        if (user == null || user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Only an admin can approve a promotion");
        }
        if (promotion.getSubmittedBy() != null && Objects.equals(promotion.getSubmittedBy(), user.getId())) {
            throw new BadRequestException("A promotion must be approved by someone other than the person who submitted it");
        }
        promotion.setApprovedBy(user.getId());
        promotion.setApprovedAt(LocalDateTime.now());
        promotion.setApprovalNote(trim(note));
        place(promotion, PromotionStatus.ACTIVE);
        audit(promotion, Action.APPROVED, user, note);
    }

    @Transactional
    public void reject(Promotion promotion, User user, String note) {
        if (promotion.getStatus() != PromotionStatus.PENDING_APPROVAL) {
            throw new BadRequestException("This promotion is not waiting for approval");
        }
        if (user == null || user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Only an admin can reject a promotion");
        }
        promotion.setApprovalNote(trim(note));
        place(promotion, PromotionStatus.DRAFT);
        audit(promotion, Action.REJECTED, user, note);
    }

    @Transactional
    public void pause(Promotion promotion, User user, String note) {
        if (promotion.getStatus() != PromotionStatus.ACTIVE) {
            throw new BadRequestException("Only an active promotion can be paused");
        }
        place(promotion, PromotionStatus.PAUSED);
        audit(promotion, Action.PAUSED, user, note);
    }

    /** Back on. A promotion whose terms were approved and unchanged does not queue again. */
    @Transactional
    public void resume(Promotion promotion, User user, Function<Long, BigDecimal> sellingPrice) {
        if (promotion.getStatus() != PromotionStatus.PAUSED) {
            throw new BadRequestException("Only a paused promotion can be resumed");
        }
        submitInternal(promotion, user, sellingPrice);
    }

    /** The legacy on/off switch, mapped onto the lifecycle so old clients keep working. */
    public void setActive(Promotion promotion, boolean active, User user, Function<Long, BigDecimal> sellingPrice) {
        if (active) {
            if (promotion.getStatus() == PromotionStatus.ACTIVE) {
                return;
            }
            if (promotion.getStatus() == PromotionStatus.PENDING_APPROVAL) {
                throw new BadRequestException("This promotion is waiting for approval");
            }
            submitInternal(promotion, user, sellingPrice);
        } else if (promotion.getStatus() == PromotionStatus.ACTIVE) {
            place(promotion, PromotionStatus.PAUSED);
            audit(promotion, Action.PAUSED, user, null);
        }
    }

    private void submitInternal(Promotion promotion, User user, Function<Long, BigDecimal> sellingPrice) {
        boolean alreadyApproved = promotion.getApprovedBy() != null;
        if (!alreadyApproved && approvalNeeded(promotion, sellingPrice)) {
            promotion.setSubmittedBy(user == null ? null : user.getId());
            place(promotion, PromotionStatus.PENDING_APPROVAL);
            audit(promotion, Action.SUBMITTED, user, null);
            return;
        }
        boolean wasPaused = promotion.getStatus() == PromotionStatus.PAUSED;
        place(promotion, PromotionStatus.ACTIVE);
        audit(promotion, wasPaused ? Action.RESUMED : Action.ACTIVATED, user, null);
    }

    /** The one place status and the engine's switch are set together. */
    private void place(Promotion promotion, PromotionStatus status) {
        promotion.setStatus(status);
        promotion.setActive(status == PromotionStatus.ACTIVE);
        snapshotCache.evict();
    }

    // ── audit ───────────────────────────────────────────────────────────────────────────

    public void audit(Promotion promotion, Action action, User user, String note) {
        if (promotion.getId() == null) {
            return;
        }
        auditRepository.save(PromotionAudit.builder()
                .promotionId(promotion.getId())
                .action(action)
                .userId(user == null ? null : user.getId())
                .username(user == null ? null : user.getUsername())
                .note(trim(note))
                .at(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<PromotionAuditDto> auditTrail(Long promotionId) {
        return auditRepository.findByPromotionIdOrderByAtDescIdDesc(promotionId).stream()
                .map(PromotionAuditDto::from)
                .toList();
    }

    private static String trim(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.length() > 255 ? note.substring(0, 255) : note.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
