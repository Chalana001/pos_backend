package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.PromotionCheckResponse;
import com.chala.posapp.dto.promotion.PromotionRequest;
import com.chala.posapp.dto.promotion.PromotionSimulationRequest;
import com.chala.posapp.dto.promotion.PromotionSimulationResponse;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.promotion.engine.LineDecision;
import com.chala.posapp.promotion.engine.LineEvaluation;
import com.chala.posapp.promotion.engine.MoneyOps;
import com.chala.posapp.promotion.engine.OrderEvaluation;
import com.chala.posapp.promotion.engine.PricingLine;
import com.chala.posapp.promotion.engine.PromotionEvaluator;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
import com.chala.posapp.promotion.engine.TargetSnapshot;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.OrderItemRepository;
import com.chala.posapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an unsaved promotion would have done, and what it will collide with.
 *
 * <p>The simulator is the pure engine run over the sales the shop actually made — the same
 * code that will price tomorrow's carts, so the projection and the reality cannot drift. It
 * runs the promotion on its own: the question is what this one costs, not how it competes.
 *
 * <p>Cheap once the engine is pure. This is what "how do I know this won't cost me two million"
 * gets as an answer instead of a shrug.
 */
@Service
@RequiredArgsConstructor
public class PromotionSimulationService {

    /** Newest orders scanned per simulation. Enough for a month at most shops; bounded for the rest. */
    static final int MAX_ORDERS = 5000;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ItemRepository itemRepository;
    private final PromotionSnapshotCache snapshotCache;
    private final PromotionLifecycleService lifecycleService;

    @Transactional(readOnly = true)
    public PromotionSimulationResponse simulate(PromotionSimulationRequest request) {
        PromotionRequest terms = request.getPromotion();
        int days = Math.max(1, request.getDays());
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(days);
        long branch = request.getBranchId() == null || request.getBranchId() <= 0 ? 0L : request.getBranchId();

        // The replay ignores the promotion's own date window — last month's sales must not be
        // gated by a start date next week — but keeps its weekday/time schedules.
        PromotionSnapshot snapshot = PromotionRequestMapper.snapshot(terms, -1L, from.minusYears(1), to.plusYears(1));
        List<PromotionSnapshot> alone = List.of(snapshot);

        List<Order> orders = orderRepository.findForReplay(OrderStatus.COMPLETED, branch, from, to, PageRequest.of(0, MAX_ORDERS));
        Map<Long, List<OrderItem>> itemsByOrder = orders.isEmpty()
                ? Map.of()
                : orderItemRepository.findByOrderIdIn(orders.stream().map(Order::getId).toList()).stream()
                        .collect(Collectors.groupingBy(OrderItem::getOrderId));
        Set<Long> itemIds = new HashSet<>();
        itemsByOrder.values().forEach(list -> list.forEach(oi -> itemIds.add(oi.getItemId())));
        Map<Long, Item> items = itemIds.isEmpty() ? Map.of()
                : itemRepository.findWithCategoryByIdIn(itemIds).stream()
                        .collect(Collectors.toMap(Item::getId, Function.identity(), (a, b) -> a));

        int affected = 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal maxOrder = BigDecimal.ZERO;
        BigDecimal marginBefore = BigDecimal.ZERO;
        Map<Long, int[]> topCount = new LinkedHashMap<>();
        Map<Long, BigDecimal> topDiscount = new LinkedHashMap<>();
        Map<Long, String> topName = new LinkedHashMap<>();

        for (Order order : orders) {
            List<OrderItem> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            if (orderItems.isEmpty()) {
                continue;
            }
            List<PricingLine> lines = new ArrayList<>();
            BigDecimal cartBase = BigDecimal.ZERO;
            for (OrderItem oi : orderItems) {
                Item item = items.get(oi.getItemId());
                if (item == null) {
                    continue;
                }
                PricingLine line = PricingLine.from(item, oi.getUnitPrice(), oi.getQty(), DiscountType.NONE, 0);
                lines.add(line);
                cartBase = cartBase.add(MoneyOps.lineTotal(item.getItemType(), line.unitPrice(), oi.getQty()));
            }
            if (lines.isEmpty() || !snapshot.isRunningAt(order.getCreatedAt())) {
                continue;
            }

            BigDecimal orderDiscount = BigDecimal.ZERO;
            BigDecimal orderMargin = BigDecimal.ZERO;
            List<PricingLine> priced = new ArrayList<>();
            boolean exclusive = false;
            for (int i = 0; i < lines.size(); i++) {
                PricingLine line = lines.get(i);
                OrderItem oi = orderItems.get(i);
                LineEvaluation evaluation = PromotionEvaluator.evaluateLine(line, order.getBranchId(), cartBase, alone);
                BigDecimal lineDiscount = BigDecimal.valueOf(evaluation.application().promotionDiscountAmount());
                orderDiscount = orderDiscount.add(lineDiscount);
                exclusive |= evaluation.application().exclusive();
                BigDecimal baseLine = BigDecimal.valueOf(evaluation.application().baseLineTotal());
                BigDecimal cost = BigDecimal.valueOf(oi.getCostPrice())
                        .multiply(MoneyOps.primaryUnits(line.itemType(), oi.getQty()));
                orderMargin = orderMargin.add(baseLine.subtract(cost));
                priced.add(line.withUnitPrice(MoneyOps.unitPriceForLineDiscount(
                        line.unitPrice(), baseLine, BigDecimal.valueOf(evaluation.application().appliedDiscountAmount()))));
                if (lineDiscount.signum() > 0) {
                    topCount.computeIfAbsent(oi.getItemId(), k -> new int[1])[0]++;
                    topDiscount.merge(oi.getItemId(), lineDiscount, BigDecimal::add);
                    topName.putIfAbsent(oi.getItemId(), oi.getItemName());
                }
            }
            BigDecimal afterLines = priced.stream()
                    .map(l -> MoneyOps.lineTotal(l.itemType(), l.unitPrice(), l.normalizedQty()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            OrderEvaluation bill = PromotionEvaluator.evaluateOrder(order.getBranchId(), order.getCustomerId(),
                    afterLines, BigDecimal.ZERO, priced, exclusive, alone);
            if (bill.application().promotionApplied()) {
                orderDiscount = orderDiscount.add(BigDecimal.valueOf(bill.application().promotionDiscountAmount()));
            }

            if (orderDiscount.signum() > 0) {
                affected++;
                total = total.add(orderDiscount);
                maxOrder = maxOrder.max(orderDiscount);
                marginBefore = marginBefore.add(orderMargin);
            }
        }

        BigDecimal daily = total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        BigDecimal budgetDays = null;
        if (terms.getBudgetAmount() != null && terms.getBudgetAmount().signum() > 0 && daily.signum() > 0) {
            budgetDays = terms.getBudgetAmount().divide(daily, 1, RoundingMode.HALF_UP);
        }
        BigDecimal marginAfter = marginBefore.subtract(total);
        BigDecimal erosion = marginBefore.signum() > 0
                ? total.multiply(BigDecimal.valueOf(100)).divide(marginBefore, 1, RoundingMode.HALF_UP)
                : null;

        List<PromotionSimulationResponse.TopItem> top = topDiscount.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(5)
                .map(e -> PromotionSimulationResponse.TopItem.builder()
                        .itemId(e.getKey()).itemName(topName.get(e.getKey()))
                        .timesDiscounted(topCount.get(e.getKey())[0])
                        .discount(MoneyOps.round2(e.getValue())).build())
                .toList();

        return PromotionSimulationResponse.builder()
                .days(days)
                .ordersScanned(orders.size())
                .ordersAffected(affected)
                .affectedRatePercent(orders.isEmpty() ? BigDecimal.ZERO
                        : BigDecimal.valueOf(affected * 100L).divide(BigDecimal.valueOf(orders.size()), 1, RoundingMode.HALF_UP))
                .projectedDiscount(MoneyOps.round2(total))
                .averageDiscountPerAffectedOrder(affected == 0 ? BigDecimal.ZERO
                        : total.divide(BigDecimal.valueOf(affected), 2, RoundingMode.HALF_UP))
                .maxOrderDiscount(MoneyOps.round2(maxOrder))
                .projectedDailyDiscount(daily)
                .budgetDaysRemaining(budgetDays)
                .grossMarginBefore(MoneyOps.round2(marginBefore))
                .grossMarginAfter(MoneyOps.round2(marginAfter))
                .marginErosionPercent(erosion)
                .topItems(top)
                .truncated(orders.size() >= MAX_ORDERS)
                .build();
    }

    /**
     * Before save: does this need a second admin, how deep does it cut, and which running
     * promotions does it collide with?
     */
    @Transactional(readOnly = true)
    public PromotionCheckResponse check(PromotionRequest request, Long excludeId) {
        Promotion asEntity = shadow(request);
        Function<Long, BigDecimal> prices = itemId -> itemRepository.findById(itemId).map(Item::getSellingPrice).orElse(null);
        boolean approval = lifecycleService.approvalNeeded(asEntity, prices);
        BigDecimal deepest = lifecycleService.deepestDiscountPercent(asEntity, prices);

        List<PromotionCheckResponse.Warning> warnings = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        if (request.getEndAt() != null && request.getEndAt().isBefore(now)) {
            warnings.add(PromotionCheckResponse.Warning.builder().code("ALREADY_ENDED")
                    .message("The end date is in the past; this promotion will never run").build());
        }
        if (request.getSchedules() != null && request.getSchedules().stream()
                .anyMatch(s -> s.getStartTime() != null && s.getEndTime() != null && s.getStartTime().equals(s.getEndTime()))) {
            warnings.add(PromotionCheckResponse.Warning.builder().code("NEVER_RUNS")
                    .message("A schedule starts and ends at the same minute").build());
        }

        PromotionSnapshot mine = PromotionRequestMapper.snapshot(request, excludeId == null ? -1L : excludeId,
                request.getStartAt(), request.getEndAt());
        for (PromotionSnapshot other : snapshotCache.candidates()) {
            if (Objects.equals(other.id(), excludeId) || !overlaps(mine, other)) {
                continue;
            }
            warnings.add(PromotionCheckResponse.Warning.builder()
                    .code("OVERLAP")
                    .promotionId(other.id()).promotionName(other.name())
                    .message(overlapMessage(mine, other))
                    .build());
        }
        return PromotionCheckResponse.builder()
                .approvalRequired(approval)
                .deepestDiscountPercent(deepest)
                .warnings(warnings)
                .build();
    }

    /** An entity-shaped view of the request, for the approval rule. Never saved. */
    private Promotion shadow(PromotionRequest request) {
        Promotion p = Promotion.builder()
                .scope(request.getScope())
                .discountType(request.getDiscountType())
                .discountValue(BigDecimal.valueOf(request.getDiscountValue()))
                .effectType(request.resolvedEffectType())
                .buyQty(request.getBuyQty()).getQty(request.getGetQty())
                .maxDiscountAmount(BigDecimal.valueOf(Math.max(0, request.getMaxDiscountAmount())))
                .allowBelowCost(request.isAllowBelowCost())
                .build();
        PromotionSnapshot snap = PromotionRequestMapper.snapshot(request, -1L, request.getStartAt(), request.getEndAt());
        for (TargetSnapshot t : snap.targets()) {
            p.getTargets().add(com.chala.posapp.entity.PromotionTarget.builder()
                    .itemId(t.itemId()).categoryId(t.categoryId()).subCategoryId(t.subCategoryId()).customerId(t.customerId())
                    .offerPrice(t.offerPrice()).discountType(t.discountType()).discountValue(t.discountValue()).build());
        }
        if (request.getTiers() != null) {
            request.getTiers().forEach(t -> p.getTiers().add(com.chala.posapp.entity.PromotionTier.builder()
                    .minQty(t.getMinQty()).minAmount(t.getMinAmount())
                    .discountType(t.getDiscountType()).discountValue(t.getDiscountValue()).build()));
        }
        return p;
    }

    private static boolean overlaps(PromotionSnapshot a, PromotionSnapshot b) {
        if (a.startAt() == null || a.endAt() == null || b.startAt() == null || b.endAt() == null) {
            return false;
        }
        if (a.endAt().isBefore(b.startAt()) || b.endAt().isBefore(a.startAt())) {
            return false;
        }
        if (a.branchId() != null && b.branchId() != null && !Objects.equals(a.branchId(), b.branchId())) {
            return false;
        }
        if (a.scope() == PromotionScope.BILL || b.scope() == PromotionScope.BILL) {
            return a.scope() == b.scope();
        }
        if (a.scope() != b.scope()) {
            return false;
        }
        Set<String> mine = keys(a);
        return keys(b).stream().anyMatch(mine::contains);
    }

    private static Set<String> keys(PromotionSnapshot p) {
        Set<String> keys = new HashSet<>();
        for (TargetSnapshot t : p.targets()) {
            if (t.itemId() != null) keys.add("i" + t.itemId());
            if (t.categoryId() != null) keys.add("c" + t.categoryId());
            if (t.subCategoryId() != null) keys.add("s" + t.subCategoryId());
            if (t.customerId() != null) keys.add("u" + t.customerId());
        }
        return keys;
    }

    private static String overlapMessage(PromotionSnapshot mine, PromotionSnapshot other) {
        String what = other.scope() == PromotionScope.BILL ? "the same bills" : "some of the same targets";
        if (mine.stackingMode() == StackingMode.EXCLUSIVE || other.stackingMode() == StackingMode.EXCLUSIVE) {
            return "Overlaps '" + other.name() + "' on " + what + "; whichever exclusive one wins blocks the other";
        }
        if (mine.stackingMode() == StackingMode.STACKABLE || other.stackingMode() == StackingMode.STACKABLE) {
            return "Overlaps '" + other.name() + "' on " + what + "; the two will stack";
        }
        return "Overlaps '" + other.name() + "' on " + what + "; the larger discount wins on each sale";
    }

    /** Kept for tests: how many decisions said APPLIED. */
    static long appliedCount(List<LineDecision> decisions) {
        return decisions.stream().filter(d -> d.outcome() == LineDecision.Outcome.APPLIED).count();
    }
}
