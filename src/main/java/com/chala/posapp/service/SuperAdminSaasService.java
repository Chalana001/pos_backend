package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.saas.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminSaasService {

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final BillingRecordRepository billingRecordRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final TenantProvisioningService tenantProvisioningService;
    private final PlatformTransactionManager transactionManager;
    private final SuperAdminAuditService auditService;
    private final SuperAdminModuleService moduleService;
    private final ModuleAccessService moduleAccessService;
    private final TenantModuleRepository tenantModuleRepository;
    private final DiscountService discountService;
    private final SubscriptionInvoiceService invoiceService;

    @Transactional(readOnly = true)
    public SuperAdminDashboardResponse getDashboard() {
        ensureSuperAdmin();

        LocalDate today = LocalDate.now();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        List<TenantSubscription> all = tenantSubscriptionRepository.findAll();

        // Run-rate and the plan split are both derived from live subscriptions rather than from
        // the billing ledger, so a shop that paid a year up front still shows its monthly worth.
        Map<Long, SuperAdminDashboardResponse.PlanBreakdown> perPlan = new LinkedHashMap<>();
        double mrr = 0.0;
        long blocked = 0;
        long newThisMonth = 0;
        List<SuperAdminDashboardResponse.ExpiringShop> expiring = new ArrayList<>();

        for (TenantSubscription subscription : all) {
            if (subscription.isBlocked()) {
                blocked++;
            }
            if (subscription.getCreatedAt() != null && !subscription.getCreatedAt().isBefore(startOfMonth)) {
                newThisMonth++;
            }

            boolean live = subscription.isActive()
                    && !subscription.isBlocked()
                    && subscription.getValidUntil().isAfter(now);

            SubscriptionPlan plan = subscription.getPlan();
            if (plan != null && live) {
                double monthly = SuperAdminPlanService.monthlyValue(plan);
                mrr += monthly;
                SuperAdminDashboardResponse.PlanBreakdown bucket = perPlan.get(plan.getId());
                if (bucket == null) {
                    perPlan.put(plan.getId(), SuperAdminDashboardResponse.PlanBreakdown.builder()
                            .planId(plan.getId())
                            .planName(plan.getName())
                            .shopCount(1)
                            .mrr(monthly)
                            .build());
                } else {
                    bucket.setShopCount(bucket.getShopCount() + 1);
                    bucket.setMrr(bucket.getMrr() + monthly);
                }
            }

            if (live && subscription.getValidUntil().isBefore(now.plusDays(30))) {
                expiring.add(SuperAdminDashboardResponse.ExpiringShop.builder()
                        .tenantId(subscription.getTenantId())
                        .shopName(subscription.getShopName())
                        .planName(plan != null ? plan.getName() : "N/A")
                        .validUntil(subscription.getValidUntil())
                        .daysLeft(java.time.Duration.between(now, subscription.getValidUntil()).toDays())
                        .build());
            }
        }

        expiring.sort(Comparator.comparing(SuperAdminDashboardResponse.ExpiringShop::getValidUntil));
        long expiring7 = expiring.stream().filter(shop -> shop.getDaysLeft() <= 7).count();

        return SuperAdminDashboardResponse.builder()
                .totalShops(all.size())
                .activeShops(tenantSubscriptionRepository.countByIsActiveTrueAndBlockedFalseAndValidUntilAfter(now))
                .expiredShops(tenantSubscriptionRepository.countByValidUntilBefore(now))
                .blockedShops(blocked)
                .totalRevenueThisMonth(round(billingRecordRepository.totalAmountBetween(startOfMonth, startOfNextMonth)))
                .estimatedMrr(round(mrr))
                .expiringWithin7Days(expiring7)
                .expiringWithin30Days(expiring.size())
                .newShopsThisMonth(newThisMonth)
                .customisedShops(tenantModuleRepository.countDistinctTenants())
                .planBreakdown(perPlan.values().stream()
                        .sorted(Comparator.comparingDouble(
                                SuperAdminDashboardResponse.PlanBreakdown::getMrr).reversed())
                        .toList())
                .expiringSoon(expiring.stream().limit(10).toList())
                .build();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @Transactional(readOnly = true)
    public PageResponse<ShopSummaryResponse> getAllShops(int page, int size, String search, String status) {
        ensureSuperAdmin();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDateTime now = LocalDateTime.now();

        Specification<TenantSubscription> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("tenantId")), term),
                        cb.like(cb.lower(root.get("shopName")), term),
                        cb.like(cb.lower(root.get("adminUsername")), term)
                ));
            }

            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                switch (status.trim().toLowerCase(Locale.ROOT)) {
                    case "active" -> predicates.add(cb.and(
                            cb.isTrue(root.get("isActive")),
                            cb.isFalse(root.get("blocked")),
                            cb.greaterThan(root.get("validUntil"), now)
                    ));
                    case "expired" -> predicates.add(cb.lessThan(root.get("validUntil"), now));
                    case "blocked" -> predicates.add(cb.isTrue(root.get("blocked")));
                    case "inactive" -> predicates.add(cb.isFalse(root.get("isActive")));
                    default -> throw new BadRequestException("Unsupported status filter: " + status);
                }
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<TenantSubscription> subscriptions = tenantSubscriptionRepository.findAll(specification, pageable);
        Map<String, Long> overrideCounts = overrideCountsByTenant();
        List<ShopSummaryResponse> items = subscriptions.getContent().stream()
                .map(subscription -> mapShop(subscription, countBranches(subscription.getTenantId()),
                        overrideCounts.getOrDefault(subscription.getTenantId(), 0L).intValue()))
                .toList();

        return PageResponse.<ShopSummaryResponse>builder()
                .items(items)
                .page(subscriptions.getNumber())
                .size(subscriptions.getSize())
                .totalElements(subscriptions.getTotalElements())
                .totalPages(subscriptions.getTotalPages())
                .first(subscriptions.isFirst())
                .last(subscriptions.isLast())
                .build();
    }

    @Transactional
    public ShopSummaryResponse onboardShop(ShopOnboardRequest request) {
        User superAdmin = ensureSuperAdmin();

        String tenantId = normalizeTenantId(request.getTenantId());
        if (tenantSubscriptionRepository.existsByTenantId(tenantId)) {
            throw new AlreadyExistsException("Tenant already exists: " + tenantId);
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));

        String adminUsername = request.getAdminUsername().trim();
        String shopName = request.getShopName().trim();
        int subscriptionCycles = request.getSubscriptionCycles() != null ? request.getSubscriptionCycles() : 1;
        ShopBusinessType businessType = parseBusinessType(request.getBusinessType());

        // A trial runs for the plan's trialDays instead of a billing cycle, and is not charged.
        boolean startTrial = Boolean.TRUE.equals(request.getStartTrial()) && plan.getTrialDays() > 0;
        LocalDateTime validUntil = startTrial
                ? LocalDateTime.now().plusDays(plan.getTrialDays())
                : extendByBillingCycle(LocalDateTime.now(), plan.getBillingCycle(), subscriptionCycles);

        TenantSubscription subscription = TenantSubscription.builder()
                .tenantId(tenantId)
                .shopName(shopName)
                .adminUsername(adminUsername)
                .plan(plan)
                .isActive(true)
                .blocked(false)
                .extraBranches(0)
                .validUntil(validUntil)
                .notes(trimToNull(request.getNote()))
                .contactPhone(trimToNull(request.getContactPhone()))
                .contactEmail(trimToNull(request.getContactEmail()))
                .businessType(businessType)
                .trial(startTrial)
                .trialEndsAt(startTrial ? validUntil : null)
                .graceDays(request.getGraceDays() == null ? 0 : request.getGraceDays())
                .build();
        tenantSubscriptionRepository.save(subscription);
        tenantProvisioningService.provisionTenantDatabase(tenantId);

        runInTenant(tenantId, () -> {
            if (userRepository.existsByUsername(adminUsername)) {
                throw new AlreadyExistsException("Admin username already exists in this tenant");
            }

            Branch branch = Branch.builder()
                    .code("MAIN")
                    .name(defaultIfBlank(request.getInitialBranchName(), "Main Branch").trim())
                    .address(trimToNull(request.getInitialBranchAddress()))
                    .phone(trimToNull(request.getInitialBranchPhone()))
                    .active(true)
                    .build();
            branchRepository.save(branch);

            User adminUser = User.builder()
                    .username(adminUsername)
                    .passwordHash(passwordEncoder.encode(request.getAdminPassword()))
                    .role(Role.ADMIN)
                    .enabled(true)
                    .branchId(null)
                    .build();
            userRepository.save(adminUser);
            return null;
        });

        // A trial is not billed; anything else takes the stated amount, or the plan price.
        double gross = startTrial
                ? 0
                : (request.getAmountPaid() != null
                        ? request.getAmountPaid()
                        : plan.getInitialPrice() * subscriptionCycles);
        double[] money = applyDiscount(request.getDiscountCode(), gross, plan.getId(), tenantId);

        BillingRecord onboardingRecord = recordBilling(
                tenantId, shopName, BillingActionType.ONBOARDING,
                money[2], money[0], money[1], trimToNull(request.getDiscountCode()),
                request.getNote(), superAdmin.getUsername());
        maybeIssueInvoice(request.getGenerateInvoice(), onboardingRecord, request.getNote());

        // Layer the business-type module tweaks on top of the plan template. Done after the
        // subscription row exists so the preset can compare against the plan's defaults.
        moduleService.applyOnboardingPreset(tenantId, businessType);

        auditService.record(superAdmin.getUsername(), "SHOP_ONBOARDED",
                SuperAdminAuditService.TARGET_SHOP, tenantId,
                "Onboarded " + shopName + " on plan " + plan.getName()
                        + " (" + businessType + ", "
                        + (startTrial ? plan.getTrialDays() + "-day trial" : subscriptionCycles + " cycle(s)")
                        + (money[1] > 0 ? ", discount " + money[1] : "") + ")");

        return mapShop(subscription, 1, 0);
    }

    private ShopBusinessType parseBusinessType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ShopBusinessType.RETAIL;
        }
        try {
            return ShopBusinessType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Business type must be RETAIL, RESTAURANT or HYBRID");
        }
    }

    @Transactional
    public ShopSummaryResponse updateShopBlockStatus(String tenantId, ShopBlockRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        subscription.setBlocked(request.isBlocked());
        subscription.setNotes(trimToNull(request.getReason()));

        auditService.record(superAdmin.getUsername(),
                request.isBlocked() ? "SHOP_BLOCKED" : "SHOP_UNBLOCKED",
                SuperAdminAuditService.TARGET_SHOP, subscription.getTenantId(),
                subscription.getShopName() + (request.isBlocked() ? " blocked" : " unblocked")
                        + (request.getReason() != null ? ": " + request.getReason() : ""));

        return mapShop(subscription, countBranches(subscription.getTenantId()), overrideCount(tenantId));
    }

    @Transactional(readOnly = true)
    public ShopDetailsResponse getShopDetails(String tenantId) {
        ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);
        long branchCount = countBranches(subscription.getTenantId());

        Branch mainBranch = runInTenant(subscription.getTenantId(), () ->
                branchRepository.findAll().stream()
                        .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                        .findFirst()
                        .orElse(null)
        );

        int baseBranches = subscription.getPlan() != null ? subscription.getPlan().getMaxBranches() : 0;
        int allowedBranches = baseBranches + subscription.getExtraBranches();

        ModuleAccessService.ModuleSnapshot modules = moduleAccessService.resolve(subscription.getTenantId());

        return ShopDetailsResponse.builder()
                .businessType(subscription.getBusinessType() != null
                        ? subscription.getBusinessType().name() : null)
                .contactPhone(subscription.getContactPhone())
                .contactEmail(subscription.getContactEmail())
                .enabledModuleCount(modules.enabledKeys().size())
                .totalModuleCount(com.chala.posapp.module.ModuleCatalog.all().size())
                .moduleOverrideCount(modules.overrides().size())
                .lifetimeValue(round(billingRecordRepository.lifetimeValueFor(subscription.getTenantId())))
                .tenantId(subscription.getTenantId())
                .shopName(subscription.getShopName())
                .adminUsername(resolveAdminUsername(subscription))
                .planName(subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A")
                .planBillingCycle(subscription.getPlan() != null ? subscription.getPlan().getBillingCycle().name() : null)
                .active(subscription.isActive())
                .blocked(subscription.isBlocked())
                .maxBranches(baseBranches)
                .extraBranches(subscription.getExtraBranches())
                .allowedBranches(allowedBranches)
                .currentBranchCount(branchCount)
                .validUntil(subscription.getValidUntil())
                .createdAt(subscription.getCreatedAt())
                .notes(subscription.getNotes())
                .mainBranchName(mainBranch != null ? mainBranch.getName() : null)
                .mainBranchAddress(mainBranch != null ? mainBranch.getAddress() : null)
                .mainBranchPhone(mainBranch != null ? mainBranch.getPhone() : null)
                .build();
    }

    @Transactional(readOnly = true)
    public List<ShopPaymentResponse> getShopPayments(String tenantId) {
        ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        return billingRecordRepository.findByTenantIdOrderByCreatedAtDesc(subscription.getTenantId()).stream()
                .map(record -> ShopPaymentResponse.builder()
                        .id(record.getId())
                        .actionType(record.getActionType().name())
                        .amount(record.getAmount())
                        .note(record.getReferenceNote())
                        .performedBy(record.getPerformedBy())
                        .createdAt(record.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public ShopSummaryResponse resetAdminPassword(String tenantId, ResetShopAdminPasswordRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        runInTenant(subscription.getTenantId(), () -> {
            User adminUser = userRepository.findByUsername(subscription.getAdminUsername())
                    .orElseGet(() -> userRepository.findFirstAdminNative()
                            .orElseThrow(() -> new ResourceNotFoundException("Tenant admin not found")));

            adminUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
            // Same reasoning as the in-shop reset: the old token must stop working now, not
            // whenever it would have expired.
            adminUser.setTokenValidFrom(LocalDateTime.now());
            userRepository.save(adminUser);
            return null;
        });

        // The new password is never written to the audit trail, only the fact of the reset.
        auditService.record(superAdmin.getUsername(), "SHOP_ADMIN_PASSWORD_RESET",
                SuperAdminAuditService.TARGET_SHOP, subscription.getTenantId(),
                "Reset admin password for " + subscription.getShopName());

        return mapShop(subscription, countBranches(subscription.getTenantId()), overrideCount(tenantId));
    }

    @Transactional
    public ShopSummaryResponse renewSubscription(String tenantId, ManualRenewRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        LocalDateTime base = subscription.getValidUntil().isAfter(LocalDateTime.now())
                ? subscription.getValidUntil()
                : LocalDateTime.now();
        subscription.setValidUntil(extendByBillingCycle(base, subscription.getPlan().getBillingCycle(), request.getCycles()));
        subscription.setActive(true);
        subscription.setNotes(trimToNull(request.getNote()));

        // Renewing converts a trial into a paid subscription.
        if (subscription.isTrial()) {
            subscription.setTrial(false);
            subscription.setTrialEndsAt(null);
        }

        double gross = request.getAmountPaid() != null
                ? request.getAmountPaid()
                : subscription.getPlan().getRenewalPrice() * request.getCycles();
        double[] money = applyDiscount(request.getDiscountCode(), gross,
                subscription.getPlan().getId(), subscription.getTenantId());
        double amount = money[2];

        BillingRecord renewalRecord = recordBilling(
                subscription.getTenantId(), subscription.getShopName(), BillingActionType.RENEWAL,
                money[2], money[0], money[1], trimToNull(request.getDiscountCode()),
                request.getNote(), superAdmin.getUsername());
        maybeIssueInvoice(request.getGenerateInvoice(), renewalRecord, request.getNote());

        auditService.record(superAdmin.getUsername(), "SHOP_RENEWED",
                SuperAdminAuditService.TARGET_SHOP, subscription.getTenantId(),
                subscription.getShopName() + " renewed for " + request.getCycles()
                        + " cycle(s) until " + subscription.getValidUntil().toLocalDate()
                        + " (" + amount + ")");

        return mapShop(subscription, countBranches(subscription.getTenantId()), overrideCount(tenantId));
    }

    @Transactional
    public ShopSummaryResponse changePackage(String tenantId, ChangePackageRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);
        SubscriptionPlan newPlan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));

        String previousPlan = subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A";
        // Worked out against the OLD plan, before it is replaced below.
        double proratedCredit = prorationCredit(subscription);
        subscription.setPlan(newPlan);
        LocalDateTime base = subscription.getValidUntil().isAfter(LocalDateTime.now())
                ? subscription.getValidUntil()
                : LocalDateTime.now();
        subscription.setValidUntil(extendByBillingCycle(base, newPlan.getBillingCycle(), 1));
        subscription.setActive(true);
        subscription.setNotes(trimToNull(request.getNote()));

        double gross = request.getAmountPaid() != null ? request.getAmountPaid() : newPlan.getInitialPrice();

        // Proration is opt-in: an upgrade should not quietly bill less than the operator
        // told the shop it would.
        double credit = Boolean.TRUE.equals(request.getProrate()) ? proratedCredit : 0;
        gross = Math.max(0, gross - credit);

        double[] money = applyDiscount(request.getDiscountCode(), gross, newPlan.getId(), subscription.getTenantId());
        double amount = money[2];

        BillingRecord changeRecord = recordBilling(
                subscription.getTenantId(), subscription.getShopName(), BillingActionType.PLAN_CHANGE,
                money[2], money[0], money[1], trimToNull(request.getDiscountCode()),
                request.getNote(), superAdmin.getUsername());
        maybeIssueInvoice(request.getGenerateInvoice(), changeRecord, request.getNote());

        // The plan carries the module template, so the cached module set is now wrong.
        // Any per-shop override the admin set deliberately survives the move.
        moduleAccessService.invalidate(subscription.getTenantId());

        auditService.record(superAdmin.getUsername(), "SHOP_PLAN_CHANGED",
                SuperAdminAuditService.TARGET_SHOP, subscription.getTenantId(),
                subscription.getShopName() + " moved from " + previousPlan + " to " + newPlan.getName()
                        + " (" + amount + (credit > 0 ? ", prorated credit " + credit : "")
                        + (money[1] > 0 ? ", discount " + money[1] : "") + ")");

        return mapShop(subscription, countBranches(subscription.getTenantId()), overrideCount(tenantId));
    }

    @Transactional
    public ShopSummaryResponse addExtraBranches(String tenantId, AddExtraBranchesRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        subscription.setExtraBranches(subscription.getExtraBranches() + request.getExtraBranches());
        subscription.setNotes(trimToNull(request.getNote()));

        double amount = request.getAmountPaid() != null ? request.getAmountPaid() : 0.0;
        recordBilling(subscription.getTenantId(), subscription.getShopName(), BillingActionType.EXTRA_BRANCHES, amount, request.getNote(), superAdmin.getUsername());

        auditService.record(superAdmin.getUsername(), "SHOP_EXTRA_BRANCHES",
                SuperAdminAuditService.TARGET_SHOP, subscription.getTenantId(),
                subscription.getShopName() + " +" + request.getExtraBranches()
                        + " branch(es), now " + subscription.getExtraBranches() + " extra (" + amount + ")");

        return mapShop(subscription, countBranches(subscription.getTenantId()), overrideCount(tenantId));
    }

    private User ensureSuperAdmin() {
        User user = authService.getLoggedUser();
        if (user.getRole() != Role.SUPER_ADMIN) {
            throw new BadRequestException("Only super admin can perform this action");
        }
        return user;
    }

    private TenantSubscription getSubscriptionOrThrow(String tenantId) {
        return tenantSubscriptionRepository.findByTenantId(normalizeTenantId(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
    }

    private BillingRecord recordBilling(String tenantId, String shopName, BillingActionType actionType,
                                        double amount, String note, String performedBy) {
        return recordBilling(tenantId, shopName, actionType, amount, amount, 0, null, note, performedBy);
    }

    /**
     * Writes one ledger line. {@code amount} stays the NET charge, as it always has been, with
     * gross and discount recorded alongside — so every existing report keeps summing the right
     * number while a discounted payment is still explainable.
     */
    private BillingRecord recordBilling(String tenantId, String shopName, BillingActionType actionType,
                                        double netAmount, double grossAmount, double discountAmount,
                                        String discountCode, String note, String performedBy) {
        return billingRecordRepository.save(BillingRecord.builder()
                .tenantId(tenantId)
                .shopName(shopName)
                .actionType(actionType)
                .amount(netAmount)
                .grossAmount(grossAmount)
                .discountAmount(discountAmount)
                .discountCode(discountCode)
                .referenceNote(trimToNull(note))
                .performedBy(performedBy)
                .build());
    }

    /**
     * Applies a discount code to a gross figure, consuming it.
     *
     * @return {gross, discount, net} — all three, because the ledger records each separately
     */
    private double[] applyDiscount(String code, double gross, Long planId, String tenantId) {
        if (code == null || code.isBlank()) {
            return new double[] { gross, 0, gross };
        }
        var applied = discountService.redeem(code, gross, planId, tenantId, null);
        return new double[] { applied.grossAmount(), applied.amountOff(), applied.netAmount() };
    }

    private void maybeIssueInvoice(Boolean requested, BillingRecord record, String note) {
        if (!Boolean.TRUE.equals(requested) || record == null) {
            return;
        }
        try {
            invoiceService.issueFor(record.getId(), note);
        } catch (Exception exception) {
            // An invoice is a document, not the payment. Failing to render one must never
            // roll back the renewal that was actually taken.
            log.warn("Could not issue an invoice for billing record {}: {}",
                    record.getId(), exception.getMessage());
        }
    }

    /**
     * Unused value left on the current plan, credited when moving to another one.
     *
     * <p>Straight-line by day: whole cycles are not assumed, because a shop that prepaid three
     * months and switches in week two should get ten weeks back, not one month.
     */
    private double prorationCredit(TenantSubscription subscription) {
        SubscriptionPlan plan = subscription.getPlan();
        if (plan == null || subscription.getValidUntil().isBefore(LocalDateTime.now())) {
            return 0;
        }
        long daysLeft = java.time.Duration.between(LocalDateTime.now(), subscription.getValidUntil()).toDays();
        if (daysLeft <= 0) {
            return 0;
        }
        double daysInCycle = plan.getBillingCycle() == BillingCycle.YEARLY ? 365.0 : 30.0;
        double perDay = plan.getRenewalPrice() / daysInCycle;
        return round(Math.min(perDay * daysLeft, plan.getRenewalPrice()));
    }

    private long countBranches(String tenantId) {
        return runInTenant(tenantId, () -> branchRepository.countAllNative());
    }

    private Map<String, Long> overrideCountsByTenant() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : tenantModuleRepository.countOverridesByTenant()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private int overrideCount(String tenantId) {
        return (int) tenantModuleRepository.countByTenantId(normalizeTenantId(tenantId));
    }

    private ShopSummaryResponse mapShop(TenantSubscription subscription, long branchCount, int moduleOverrideCount) {
        int baseBranches = subscription.getPlan() != null ? subscription.getPlan().getMaxBranches() : 0;
        int allowedBranches = baseBranches + subscription.getExtraBranches();

        return ShopSummaryResponse.builder()
                .businessType(subscription.getBusinessType() != null
                        ? subscription.getBusinessType().name() : null)
                .moduleOverrideCount(moduleOverrideCount)
                .tenantId(subscription.getTenantId())
                .shopName(subscription.getShopName())
                .adminUsername(resolveAdminUsername(subscription))
                .planName(subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A")
                .planBillingCycle(subscription.getPlan() != null ? subscription.getPlan().getBillingCycle().name() : null)
                .active(subscription.isActive())
                .blocked(subscription.isBlocked())
                .maxBranches(baseBranches)
                .extraBranches(subscription.getExtraBranches())
                .allowedBranches(allowedBranches)
                .currentBranchCount(branchCount)
                .validUntil(subscription.getValidUntil())
                .createdAt(subscription.getCreatedAt())
                .build();
    }

    private String resolveAdminUsername(TenantSubscription subscription) {
        String adminUsername = subscription.getAdminUsername();
        if (adminUsername == null || adminUsername.isBlank()) {
            adminUsername = userRepository.findFirstAdminNative()
                    .map(User::getUsername)
                    .orElse("N/A");
        }
        return adminUsername;
    }

    private LocalDateTime extendByBillingCycle(LocalDateTime from, BillingCycle billingCycle, int cycles) {
        return switch (billingCycle) {
            case YEARLY -> from.plusYears(cycles);
            case MONTHLY -> from.plusMonths(cycles);
        };
    }

    private String normalizeTenantId(String rawTenantId) {
        String tenantId = rawTenantId == null ? "" : rawTenantId.trim().toLowerCase(Locale.ROOT);
        tenantId = tenantId.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        tenantId = tenantId.replaceAll("^-|-$", "");
        if (tenantId.isBlank()) {
            throw new BadRequestException("Tenant ID is required");
        }
        if ("master".equals(tenantId)) {
            throw new BadRequestException("Tenant ID cannot be MASTER");
        }
        return tenantId;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private <T> T runInTenant(String tenantId, Supplier<T> supplier) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // TenantContext must be set BEFORE the transaction begins so Hibernate opens
        // the session with the correct catalog. Wrapping execute() inside callWith()
        // ensures resolveCurrentTenantIdentifier() sees the right tenant at session-open time.
        return TenantContext.callWith(tenantId,
                () -> transactionTemplate.execute(status -> supplier.get()));
    }
}
