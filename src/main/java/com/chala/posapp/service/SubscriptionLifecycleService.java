package com.chala.posapp.service;

import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The subscription clock: what is about to lapse, what is in grace, and what has run out.
 *
 * <p>Two things happen here that used to happen nowhere:
 * <ul>
 *   <li><strong>The renewal queue.</strong> {@link #renewalQueue()} is what the panel shows an
 *       operator each morning, bucketed by urgency, so a lapse is something you act on rather
 *       than something a shop phones you about.</li>
 *   <li><strong>Trial expiry.</strong> A trial ends by itself. The nightly sweep deactivates
 *       trials past their end date instead of leaving them running forever.</li>
 * </ul>
 *
 * <p>Reminders are recorded on the subscription ({@code last_reminder_type}) rather than sent
 * from here — this platform has no outbound channel wired for shop owners, so marking a
 * reminder as done is the operator's action after they actually call. Recording it is what
 * stops two people chasing the same shop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleService {

    /** Buckets the panel groups the queue into. */
    public enum Urgency { OVERDUE, GRACE, TODAY, WEEK, MONTH }

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SuperAdminAuditService auditService;

    @Value("${app.subscription.trial-sweep-enabled:true}")
    private boolean trialSweepEnabled;

    public record RenewalItem(
            String tenantId,
            String shopName,
            String planName,
            String contactPhone,
            String contactEmail,
            boolean trial,
            LocalDateTime validUntil,
            LocalDateTime accessEndsAt,
            long daysLeft,
            String urgency,
            boolean inGrace,
            int graceDays,
            String lastReminderType,
            LocalDateTime lastReminderAt,
            double renewalPrice
    ) {
    }

    public record RenewalQueue(
            int overdue,
            int inGrace,
            int dueToday,
            int dueThisWeek,
            int dueThisMonth,
            int trialsEndingSoon,
            List<RenewalItem> items
    ) {
    }

    /**
     * Everything worth chasing: already lapsed, in grace, or due within 30 days. Sorted by
     * how soon it matters, so the top of the list is always the most urgent.
     */
    @Transactional(readOnly = true)
    public RenewalQueue renewalQueue() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime horizon = now.plusDays(30);

        List<RenewalItem> items = new ArrayList<>();
        int overdue = 0, grace = 0, today = 0, week = 0, month = 0, trials = 0;

        for (TenantSubscription subscription : tenantSubscriptionRepository.findAll()) {
            if (!subscription.isActive() || subscription.isBlocked()) {
                continue;
            }
            LocalDateTime validUntil = subscription.getValidUntil();
            if (validUntil.isAfter(horizon)) {
                continue;
            }

            boolean inGrace = subscription.isWithinGrace();
            long daysLeft = Duration.between(now, validUntil).toDays();

            Urgency urgency;
            if (inGrace) {
                urgency = Urgency.GRACE;
                grace++;
            } else if (validUntil.isBefore(now)) {
                urgency = Urgency.OVERDUE;
                overdue++;
            } else if (daysLeft <= 0) {
                urgency = Urgency.TODAY;
                today++;
            } else if (daysLeft <= 7) {
                urgency = Urgency.WEEK;
                week++;
            } else {
                urgency = Urgency.MONTH;
                month++;
            }

            if (subscription.isTrial()) {
                trials++;
            }

            SubscriptionPlan plan = subscription.getPlan();
            items.add(new RenewalItem(
                    subscription.getTenantId(),
                    subscription.getShopName(),
                    plan != null ? plan.getName() : "N/A",
                    subscription.getContactPhone(),
                    subscription.getContactEmail(),
                    subscription.isTrial(),
                    validUntil,
                    subscription.getAccessEndsAt(),
                    daysLeft,
                    urgency.name(),
                    inGrace,
                    subscription.getGraceDays(),
                    subscription.getLastReminderType(),
                    subscription.getLastReminderAt(),
                    plan != null ? plan.getRenewalPrice() : 0));
        }

        items.sort(Comparator.comparing(RenewalItem::validUntil));
        return new RenewalQueue(overdue, grace, today, week, month, trials, items);
    }

    /** Marks that a shop has been chased, so the next operator does not chase it again. */
    @Transactional
    public void markReminded(String tenantId, String reminderType, String note) {
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new com.chala.posapp.exception.ResourceNotFoundException(
                        "Shop not found: " + tenantId));

        subscription.setLastReminderType(reminderType == null ? "CONTACTED" : reminderType);
        subscription.setLastReminderAt(LocalDateTime.now());
        tenantSubscriptionRepository.save(subscription);

        auditService.record(currentActor(), "RENEWAL_REMINDER_LOGGED",
                SuperAdminAuditService.TARGET_SHOP, tenantId,
                subscription.getShopName() + " contacted about renewal"
                        + (note != null && !note.isBlank() ? " — " + note : ""));
    }

    /** Sets or clears the grace window for one shop. */
    @Transactional
    public void setGraceDays(String tenantId, int graceDays) {
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new com.chala.posapp.exception.ResourceNotFoundException(
                        "Shop not found: " + tenantId));

        int clamped = Math.max(0, Math.min(graceDays, 60));
        subscription.setGraceDays(clamped);
        tenantSubscriptionRepository.save(subscription);

        auditService.record(currentActor(), "GRACE_PERIOD_SET", SuperAdminAuditService.TARGET_SHOP, tenantId,
                subscription.getShopName() + ": grace period set to " + clamped + " day(s)");
    }

    /**
     * Nightly sweep that ends trials whose date has passed.
     *
     * <p>Runs in MASTER context because the scheduler thread has no tenant of its own, and
     * deactivates rather than deletes: the shop's data is untouched and a single renew brings
     * it straight back.
     */
    @Scheduled(cron = "${app.subscription.trial-sweep-cron:0 15 1 * * *}")
    public void expireFinishedTrials() {
        if (!trialSweepEnabled) {
            return;
        }
        TenantContext.runWith("MASTER", () -> {
            LocalDateTime now = LocalDateTime.now();
            List<TenantSubscription> expired = tenantSubscriptionRepository.findAll().stream()
                    .filter(TenantSubscription::isTrial)
                    .filter(TenantSubscription::isActive)
                    .filter(subscription -> subscription.getTrialEndsAt() != null)
                    .filter(subscription -> subscription.getTrialEndsAt().isBefore(now))
                    .filter(subscription -> subscription.getAccessEndsAt().isBefore(now))
                    .toList();

            for (TenantSubscription subscription : expired) {
                subscription.setActive(false);
                tenantSubscriptionRepository.save(subscription);
                auditService.record("system", "TRIAL_EXPIRED", SuperAdminAuditService.TARGET_SHOP,
                        subscription.getTenantId(),
                        subscription.getShopName() + "'s trial ended on "
                                + subscription.getTrialEndsAt().toLocalDate() + " — deactivated");
                log.info("Trial expired for tenant {}", subscription.getTenantId());
            }

            if (!expired.isEmpty()) {
                log.info("Trial sweep deactivated {} shop(s).", expired.size());
            }
        });
    }

    private String currentActor() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
