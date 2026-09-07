package com.chala.posapp.service;

import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.PromotionCode;
import com.chala.posapp.entity.PromotionRedemption;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.promotion.engine.LineDecision;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;
import com.chala.posapp.repository.PromotionCodeRepository;
import com.chala.posapp.repository.PromotionRedemptionRepository;
import com.chala.posapp.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the redemption ledger and moves the counters, inside the sale's own transaction.
 *
 * <p>{@link #record} is the only thing that consumes a code or a promotion's cap, and it runs
 * with {@code MANDATORY} propagation: it must be called from inside the order transaction, so a
 * sale that fails for any later reason never leaves a code half-used. Mirrors
 * {@code DiscountService.redeem} on the SaaS side, which got this right first.
 *
 * <p>Consumption is a conditional UPDATE, not read-modify-write. If it touches no row, the cap
 * was taken by another till between the preview and this commit, and the sale is refused
 * rather than a code redeemed twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionRedemptionService {

    private final PromotionRepository promotionRepository;
    private final PromotionCodeRepository codeRepository;
    private final PromotionRedemptionRepository redemptionRepository;

    /** One saved line with the engine's verdicts for it. */
    public record LineRedemption(Long orderItemId, Long itemId, List<LineDecision> decisions) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Order order, List<LineRedemption> lines, List<LineDecision> billDecisions, PromotionCode code) {
        LocalDateTime now = LocalDateTime.now();
        Long codePromotionId = code == null ? null : code.getPromotion().getId();
        List<PromotionRedemption> rows = new ArrayList<>();
        Map<Long, BigDecimal> perPromotion = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();

        for (LineRedemption line : lines == null ? List.<LineRedemption>of() : lines) {
            for (LineDecision decision : line.decisions()) {
                if (decision.outcome() != Outcome.APPLIED) {
                    continue;
                }
                rows.add(row(order, decision, PromotionRedemption.Level.LINE, line.orderItemId(), line.itemId(), codePromotionId, code, now));
                perPromotion.merge(decision.promotionId(), decision.discount(), BigDecimal::add);
                names.put(decision.promotionId(), decision.promotionName());
            }
        }
        for (LineDecision decision : billDecisions == null ? List.<LineDecision>of() : billDecisions) {
            if (decision.outcome() != Outcome.APPLIED) {
                continue;
            }
            rows.add(row(order, decision, PromotionRedemption.Level.BILL, null, null, codePromotionId, code, now));
            perPromotion.merge(decision.promotionId(), decision.discount(), BigDecimal::add);
            names.put(decision.promotionId(), decision.promotionName());
        }
        if (rows.isEmpty()) {
            return;
        }

        for (Map.Entry<Long, BigDecimal> entry : perPromotion.entrySet()) {
            if (promotionRepository.consume(entry.getKey(), entry.getValue()) == 0) {
                throw new BadRequestException("Promotion '" + names.get(entry.getKey())
                        + "' has just reached its limit. Remove it and try again.");
            }
        }
        if (code != null && perPromotion.containsKey(codePromotionId)) {
            if (codeRepository.consume(code.getId()) == 0) {
                throw new BadRequestException("Code " + code.getCode() + " has just been fully redeemed.");
            }
        }
        redemptionRepository.saveAll(rows);
    }

    /** One line of an imported offline sale with the promotion the till says it applied. */
    public record OfflineLine(Long orderItemId, Long itemId, Long promotionId, String promotionName, BigDecimal discount) {
    }

    /**
     * Books what an offline till already gave away.
     *
     * <p>The sale happened; the customer paid the discounted price and holds the receipt. So
     * this never refuses: rows are written from the till's attribution and the caps are
     * counted up with no ceiling. A promotion that was at its limit while the till was offline
     * overshoots by that sale — recorded, visible, and the honest alternative to booking a
     * discount the customer did not get or refusing a sale that has already happened.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOffline(Order order, List<OfflineLine> lines,
                              Long billPromotionId, String billPromotionName, BigDecimal billDiscount) {
        LocalDateTime now = LocalDateTime.now();
        List<PromotionRedemption> rows = new ArrayList<>();
        Map<Long, BigDecimal> perPromotion = new LinkedHashMap<>();

        for (OfflineLine line : lines == null ? List.<OfflineLine>of() : lines) {
            if (line.promotionId() == null || line.discount() == null || line.discount().signum() <= 0) {
                continue;
            }
            rows.add(offlineRow(order, line.promotionId(), PromotionRedemption.Level.LINE,
                    line.orderItemId(), line.itemId(), line.discount(), now));
            perPromotion.merge(line.promotionId(), line.discount(), BigDecimal::add);
        }
        if (billPromotionId != null && billDiscount != null && billDiscount.signum() > 0) {
            rows.add(offlineRow(order, billPromotionId, PromotionRedemption.Level.BILL, null, null, billDiscount, now));
            perPromotion.merge(billPromotionId, billDiscount, BigDecimal::add);
        }
        if (rows.isEmpty()) {
            return;
        }
        perPromotion.forEach(promotionRepository::consumeUnchecked);
        redemptionRepository.saveAll(rows);
        log.info("Booked {} offline promotion redemption(s) on order {} (bundle {})",
                rows.size(), order.getId(), order.getPromotionBundleVersion());
    }

    private PromotionRedemption offlineRow(Order order, Long promotionId, PromotionRedemption.Level level,
                                           Long orderItemId, Long itemId, BigDecimal amount, LocalDateTime now) {
        return PromotionRedemption.builder()
                .promotionId(promotionId)
                .orderId(order.getId())
                .orderItemId(orderItemId)
                .itemId(itemId)
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .userId(order.getCashierUserId())
                .level(level)
                .discountAmount(amount)
                .redeemedAt(order.getOfflineSoldAt() != null ? order.getOfflineSoldAt() : now)
                .build();
    }

    /** One returned line: which order item, and what share of it came back. */
    public record ReturnedLine(Long orderItemId, BigDecimal share) {
    }

    /**
     * Gives back the part of each promotion that a return undid.
     *
     * <p>Proportional to the quantity returned, and never more than is left: two partial returns
     * that together take the whole line give back exactly the whole discount, because each looks
     * at what earlier ones already released.
     *
     * <p>The budget is released — that money genuinely came back — but the redemption count is
     * not. A customer who returned one of three items still used the promotion on that order,
     * and a per-customer cap should go on saying so. A <em>full</em> return is a different
     * thing: the caller sends it to {@link #reverseForOrder}, which undoes the sale entirely.
     */
    @Transactional
    public void reverseForReturn(Long orderId, Long orderReturnId, Long userId, List<ReturnedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<PromotionRedemption> rows = new ArrayList<>();
        Map<Long, BigDecimal> perPromotion = new LinkedHashMap<>();

        for (ReturnedLine line : lines) {
            if (line.share() == null || line.share().signum() <= 0) {
                continue;
            }
            for (PromotionRedemption original : redemptionRepository.findLiveForOrderItem(line.orderItemId())) {
                BigDecimal alreadyBack = redemptionRepository.reversedSoFar(original.getId()).abs();
                BigDecimal remaining = original.getDiscountAmount().subtract(alreadyBack);
                if (remaining.signum() <= 0) {
                    continue;
                }
                BigDecimal giveBack = original.getDiscountAmount()
                        .multiply(line.share())
                        .setScale(2, RoundingMode.HALF_UP)
                        .min(remaining);
                if (giveBack.signum() <= 0) {
                    continue;
                }
                rows.add(PromotionRedemption.builder()
                        .promotionId(original.getPromotionId())
                        .promotionCodeId(original.getPromotionCodeId())
                        .orderId(orderId)
                        .orderItemId(original.getOrderItemId())
                        .itemId(original.getItemId())
                        .customerId(original.getCustomerId())
                        .branchId(original.getBranchId())
                        .userId(userId)
                        .level(original.getLevel())
                        .discountAmount(giveBack.negate())
                        .redeemedAt(now)
                        .reversalOfId(original.getId())
                        .orderReturnId(orderReturnId)
                        .build());
                perPromotion.merge(original.getPromotionId(), giveBack, BigDecimal::add);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        // Budget only. The money came back; the promotion was still used on this order.
        perPromotion.forEach(promotionRepository::releaseBudget);
        redemptionRepository.saveAll(rows);
        log.info("Return {} gave back {} promotion redemption part(s) on order {}",
                orderReturnId, rows.size(), orderId);
    }

    /**
     * Gives back every redemption on an order. The rows stay — reversed, not deleted — and the
     * counters and any code they consumed are released so the cap frees up.
     */
    @Transactional
    public void reverseForOrder(Long orderId, Long reversalOrderId) {
        List<PromotionRedemption> rows = redemptionRepository.findByOrderIdAndReversedAtIsNull(orderId);
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> perPromotion = new LinkedHashMap<>();
        for (PromotionRedemption row : rows) {
            perPromotion.merge(row.getPromotionId(), row.getDiscountAmount(), BigDecimal::add);
        }
        perPromotion.forEach(promotionRepository::release);
        rows.stream()
                .map(PromotionRedemption::getPromotionCodeId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(codeRepository::release);
        redemptionRepository.reverseForOrder(orderId, reversalOrderId, LocalDateTime.now());
        log.info("Reversed {} promotion redemption(s) on order {}", rows.size(), orderId);
    }

    private PromotionRedemption row(Order order, LineDecision decision, PromotionRedemption.Level level,
                                    Long orderItemId, Long itemId, Long codePromotionId, PromotionCode code,
                                    LocalDateTime now) {
        boolean viaCode = code != null && Objects.equals(codePromotionId, decision.promotionId());
        return PromotionRedemption.builder()
                .promotionId(decision.promotionId())
                .promotionCodeId(viaCode ? code.getId() : null)
                .orderId(order.getId())
                .orderItemId(orderItemId)
                .itemId(itemId)
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .userId(order.getCashierUserId())
                .level(level)
                .discountAmount(decision.discount())
                .redeemedAt(now)
                .build();
    }
}
