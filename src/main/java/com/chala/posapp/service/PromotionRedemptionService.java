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
