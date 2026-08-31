package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.saas.BillingEntryResponse;
import com.chala.posapp.dto.saas.RevenueOverviewResponse;
import com.chala.posapp.entity.BillingRecord;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.BillingRecordRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the billing ledger the rest of the system already writes.
 *
 * <p>{@code SuperAdminSaasService} has been recording a {@link BillingRecord} for every
 * onboarding, renewal, plan change and extra-branch purchase since the SaaS layer shipped, but
 * nothing read those rows beyond a single {@code SUM} on the dashboard. This turns them into the
 * revenue view.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminBillingService {

    /** How many months of history the trend chart shows. */
    private static final int TREND_MONTHS = 12;

    private final BillingRecordRepository billingRecordRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;

    @Transactional(readOnly = true)
    public RevenueOverviewResponse getOverview() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = startOfMonth.plusMonths(1);
        LocalDateTime startOfLastMonth = startOfMonth.minusMonths(1);

        double thisMonth = billingRecordRepository.totalAmountBetween(startOfMonth, startOfNextMonth);
        double lastMonth = billingRecordRepository.totalAmountBetween(startOfLastMonth, startOfMonth);
        Double growth = lastMonth > 0 ? ((thisMonth - lastMonth) / lastMonth) * 100.0 : null;

        double lifetimeTotal = billingRecordRepository.totalAmountBetween(
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.now().plusYears(1));

        List<RevenueOverviewResponse.ActionBucket> byAction =
                billingRecordRepository.totalsByActionType(LocalDateTime.of(2000, 1, 1, 0, 0),
                                LocalDateTime.now().plusYears(1)).stream()
                        .map(row -> new RevenueOverviewResponse.ActionBucket(
                                String.valueOf(row[0]), toDouble(row[1]), toLong(row[2])))
                        .toList();

        List<RevenueOverviewResponse.MonthPoint> trend = buildTrend();

        // Run-rate is computed from live subscriptions, not from the ledger — a shop that paid
        // a year up front still contributes 1/12 of its plan every month.
        List<TenantSubscription> active = tenantSubscriptionRepository.findByIsActiveTrue().stream()
                .filter(subscription -> !subscription.isBlocked())
                .filter(subscription -> subscription.getValidUntil().isAfter(LocalDateTime.now()))
                .toList();

        Map<Long, PlanAccumulator> perPlan = new LinkedHashMap<>();
        double mrr = 0.0;
        for (TenantSubscription subscription : active) {
            SubscriptionPlan plan = subscription.getPlan();
            if (plan == null) {
                continue;
            }
            double monthly = SuperAdminPlanService.monthlyValue(plan);
            mrr += monthly;
            perPlan.computeIfAbsent(plan.getId(), key -> new PlanAccumulator(plan.getName()))
                    .add(monthly);
        }

        Map<String, Double> bookedByTenant = new LinkedHashMap<>();
        for (Object[] row : billingRecordRepository.lifetimeValueByTenant(Pageable.unpaged())) {
            bookedByTenant.put(String.valueOf(row[0]), toDouble(row[2]));
        }
        for (TenantSubscription subscription : tenantSubscriptionRepository.findAll()) {
            SubscriptionPlan plan = subscription.getPlan();
            if (plan == null) {
                continue;
            }
            perPlan.computeIfAbsent(plan.getId(), key -> new PlanAccumulator(plan.getName()))
                    .addBooked(bookedByTenant.getOrDefault(subscription.getTenantId(), 0.0));
        }

        List<RevenueOverviewResponse.PlanBucket> planBuckets = perPlan.entrySet().stream()
                .map(entry -> new RevenueOverviewResponse.PlanBucket(
                        entry.getKey(),
                        entry.getValue().planName,
                        entry.getValue().shopCount,
                        round(entry.getValue().mrr),
                        round(entry.getValue().booked)))
                .sorted((left, right) -> Double.compare(right.mrr(), left.mrr()))
                .toList();

        List<RevenueOverviewResponse.ShopValue> topShops =
                billingRecordRepository.lifetimeValueByTenant(PageRequest.of(0, 10)).stream()
                        .map(row -> new RevenueOverviewResponse.ShopValue(
                                String.valueOf(row[0]),
                                row[1] == null ? String.valueOf(row[0]) : String.valueOf(row[1]),
                                toDouble(row[2]),
                                toLong(row[3])))
                        .toList();

        long payingShops = active.size();
        double arpu = payingShops == 0 ? 0.0 : mrr / payingShops;

        return new RevenueOverviewResponse(
                round(thisMonth),
                round(lastMonth),
                growth == null ? null : round(growth),
                round(lifetimeTotal),
                round(mrr),
                round(mrr * 12),
                payingShops,
                round(arpu),
                byAction,
                trend,
                planBuckets,
                topShops);
    }

    /**
     * Twelve months of history with the empty months filled in — a gap in the ledger should
     * draw as a zero, not close the gap and imply the months were consecutive.
     */
    private List<RevenueOverviewResponse.MonthPoint> buildTrend() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(TREND_MONTHS - 1L);
        Map<String, Object[]> byKey = new LinkedHashMap<>();
        for (Object[] row : billingRecordRepository.monthlyTotalsSince(start.atStartOfDay())) {
            byKey.put(toInt(row[0]) + "-" + toInt(row[1]), row);
        }

        List<RevenueOverviewResponse.MonthPoint> points = new ArrayList<>();
        for (int offset = 0; offset < TREND_MONTHS; offset++) {
            LocalDate month = start.plusMonths(offset);
            Object[] row = byKey.get(month.getYear() + "-" + month.getMonthValue());
            points.add(new RevenueOverviewResponse.MonthPoint(
                    month.getYear(),
                    month.getMonthValue(),
                    Month.of(month.getMonthValue()).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                            + " " + month.getYear(),
                    row == null ? 0.0 : round(toDouble(row[2])),
                    row == null ? 0L : toLong(row[3])));
        }
        return points;
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingEntryResponse> getLedger(int page, int size, String search,
                                                        String actionType, String from, String to) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<BillingRecord> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("tenantId")), term),
                        cb.like(cb.lower(root.get("shopName")), term),
                        cb.like(cb.lower(root.get("performedBy")), term)));
            }
            if (actionType != null && !actionType.isBlank() && !"all".equalsIgnoreCase(actionType)) {
                predicates.add(cb.equal(root.get("actionType"),
                        com.chala.posapp.entity.BillingActionType.valueOf(
                                actionType.trim().toUpperCase(Locale.ROOT))));
            }
            parseDate(from, false).ifPresent(value ->
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), value)));
            parseDate(to, true).ifPresent(value ->
                    predicates.add(cb.lessThan(root.get("createdAt"), value)));

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<BillingRecord> results = billingRecordRepository.findAll(specification, pageable);

        return PageResponse.<BillingEntryResponse>builder()
                .items(results.getContent().stream().map(this::toEntry).toList())
                .page(results.getNumber())
                .size(results.getSize())
                .totalElements(results.getTotalElements())
                .totalPages(results.getTotalPages())
                .first(results.isFirst())
                .last(results.isLast())
                .build();
    }

    private BillingEntryResponse toEntry(BillingRecord record) {
        return new BillingEntryResponse(
                record.getId(),
                record.getTenantId(),
                record.getShopName(),
                record.getActionType().name(),
                record.getAmount(),
                record.getReferenceNote(),
                record.getPerformedBy(),
                record.getCreatedAt());
    }

    /** {@code exclusiveEnd} turns a "to" date into the start of the following day. */
    private java.util.Optional<LocalDateTime> parseDate(String raw, boolean exclusiveEnd) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            LocalDate date = LocalDate.parse(raw.trim());
            return java.util.Optional.of(exclusiveEnd ? date.plusDays(1).atStartOfDay() : date.atStartOfDay());
        } catch (Exception exception) {
            throw new BadRequestException("Invalid date (expected yyyy-MM-dd): " + raw);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static final class PlanAccumulator {
        private final String planName;
        private long shopCount;
        private double mrr;
        private double booked;

        private PlanAccumulator(String planName) {
            this.planName = planName;
        }

        void add(double monthly) {
            shopCount++;
            mrr += monthly;
        }

        void addBooked(double amount) {
            booked += amount;
        }
    }
}
