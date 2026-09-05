package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.CodeCheckResponse;
import com.chala.posapp.entity.PromotionCode;
import com.chala.posapp.promotion.engine.LineDecision;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
import com.chala.posapp.repository.PromotionCodeRepository;
import com.chala.posapp.repository.PromotionRedemptionRepository;
import com.chala.posapp.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides which of the running promotions this particular sale may use.
 *
 * <p>The engine is pure and knows nothing about codes or counts; this sits in front of it. It
 * takes the cached candidate set and removes what a code, a redemption cap, a budget or a
 * per-customer limit rules out, saying why for each — so "why did my promotion not apply?" has
 * an answer for these reasons too, not just the engine's.
 *
 * <p>Usage counters are read fresh here, not from the cached snapshot: they move on every sale,
 * and evicting a tenant's promotion cache every time a promotion fires would defeat caching.
 * One indexed query fetches the moving part for the candidates at hand.
 */
@Component
@RequiredArgsConstructor
public class PromotionGate {

    private final PromotionRepository promotionRepository;
    private final PromotionCodeRepository codeRepository;
    private final PromotionRedemptionRepository redemptionRepository;

    /**
     * @param eligible   what the engine may now consider
     * @param excluded   one decision per candidate this gate removed
     * @param code       the presented code, resolved and valid for one of the eligible promotions — or null
     * @param codeStatus what to tell the till about the presented code; null when none was presented
     */
    public record Result(List<PromotionSnapshot> eligible, List<LineDecision> excluded,
                         PromotionCode code, CodeCheckResponse codeStatus) {
    }

    private record LimitState(Integer maxTotal, Integer maxPerCustomer, BigDecimal budget,
                              int timesRedeemed, BigDecimal budgetConsumed, long codeCount) {
        boolean codeGated() { return codeCount > 0; }
        boolean limitReached() { return maxTotal != null && timesRedeemed >= maxTotal; }
        boolean budgetExhausted() { return budget != null && budgetConsumed != null && budgetConsumed.compareTo(budget) >= 0; }
    }

    @Transactional(readOnly = true)
    public Result gate(List<PromotionSnapshot> candidates, String presentedCode, Long customerId, LocalDateTime now) {
        List<PromotionSnapshot> pool = candidates == null ? List.of() : candidates;
        Map<Long, LimitState> states = limitStates(pool);

        PromotionCode code = null;
        CodeCheckResponse codeStatus = null;
        if (presentedCode != null && !presentedCode.isBlank()) {
            String normalized = presentedCode.trim().toUpperCase();
            PromotionCode found = codeRepository.findByCodeIgnoreCase(normalized).orElse(null);
            String rejection = rejectCode(found, pool, customerId, now);
            if (rejection == null) {
                code = found;
                codeStatus = CodeCheckResponse.builder()
                        .code(normalized).valid(true)
                        .promotionId(found.getPromotion().getId())
                        .promotionName(found.getPromotion().getName())
                        .build();
            } else {
                codeStatus = CodeCheckResponse.builder()
                        .code(normalized).valid(false)
                        .promotionId(found == null ? null : found.getPromotion().getId())
                        .promotionName(found == null ? null : found.getPromotion().getName())
                        .message(rejection)
                        .build();
            }
        }

        List<PromotionSnapshot> eligible = new ArrayList<>();
        List<LineDecision> excluded = new ArrayList<>();
        for (PromotionSnapshot promotion : pool) {
            LimitState state = states.get(promotion.id());
            if (state == null) {
                eligible.add(promotion);
                continue;
            }
            if (state.codeGated() && (code == null || !Objects.equals(code.getPromotion().getId(), promotion.id()))) {
                excluded.add(LineDecision.of(promotion.id(), promotion.name(), Outcome.CODE_REQUIRED));
                continue;
            }
            if (state.limitReached()) {
                excluded.add(LineDecision.of(promotion.id(), promotion.name(), Outcome.LIMIT_REACHED));
                continue;
            }
            if (state.budgetExhausted()) {
                excluded.add(LineDecision.of(promotion.id(), promotion.name(), Outcome.BUDGET_EXHAUSTED));
                continue;
            }
            // A per-customer cap can only be enforced when there is a customer on the sale. A
            // walk-in is anonymous by definition; the cap is not a reason to refuse them.
            if (state.maxPerCustomer() != null && customerId != null && customerId > 0
                    && redemptionRepository.countOrdersForCustomer(promotion.id(), customerId) >= state.maxPerCustomer()) {
                excluded.add(LineDecision.of(promotion.id(), promotion.name(), Outcome.CUSTOMER_LIMIT_REACHED));
                continue;
            }
            eligible.add(promotion);
        }
        return new Result(eligible, excluded, code, codeStatus);
    }

    /** The message a customer should hear, or null if the code is good for this sale. */
    private String rejectCode(PromotionCode code, List<PromotionSnapshot> pool, Long customerId, LocalDateTime now) {
        if (code == null) {
            return "Unknown code";
        }
        String reason = code.rejectionReason(now);
        if (reason != null) {
            return reason;
        }
        Long promotionId = code.getPromotion().getId();
        if (pool.stream().noneMatch(p -> Objects.equals(p.id(), promotionId))) {
            return "This code is not valid right now";
        }
        Integer perCustomer = code.getPerCustomerLimit();
        if (perCustomer == null && code.getCodeType() == PromotionCode.CodeType.PER_CUSTOMER) {
            perCustomer = 1;
        }
        if (perCustomer != null) {
            if (customerId == null || customerId <= 0) {
                return "This code needs a customer on the sale";
            }
            if (redemptionRepository.countOrdersForCodeAndCustomer(code.getId(), customerId) >= perCustomer) {
                return "This customer has already used this code";
            }
        }
        return null;
    }

    private Map<Long, LimitState> limitStates(List<PromotionSnapshot> pool) {
        Map<Long, LimitState> states = new HashMap<>();
        if (pool.isEmpty()) {
            return states;
        }
        List<Long> ids = pool.stream().map(PromotionSnapshot::id).filter(Objects::nonNull).toList();
        for (Object[] row : promotionRepository.limitStateRaw(ids)) {
            states.put(toLong(row[0]), new LimitState(
                    toInt(row[1]), toInt(row[2]), toDecimal(row[3]),
                    toInt(row[4]) == null ? 0 : toInt(row[4]), toDecimal(row[5]),
                    toLong(row[6]) == null ? 0 : toLong(row[6])));
        }
        return states;
    }

    private static Long toLong(Object v) { return v == null ? null : ((Number) v).longValue(); }
    private static Integer toInt(Object v) { return v == null ? null : ((Number) v).intValue(); }
    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        return v instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) v).doubleValue());
    }
}
