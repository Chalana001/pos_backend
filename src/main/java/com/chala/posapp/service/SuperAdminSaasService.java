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
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

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

    @Transactional(readOnly = true)
    public SuperAdminDashboardResponse getDashboard() {
        ensureSuperAdmin();

        LocalDate today = LocalDate.now();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        return SuperAdminDashboardResponse.builder()
                .totalShops(tenantSubscriptionRepository.count())
                .activeShops(tenantSubscriptionRepository.countByIsActiveTrueAndBlockedFalseAndValidUntilAfter(now))
                .expiredShops(tenantSubscriptionRepository.countByValidUntilBefore(now))
                .totalRevenueThisMonth(billingRecordRepository.totalAmountBetween(startOfMonth, startOfNextMonth))
                .build();
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
        List<ShopSummaryResponse> items = subscriptions.getContent().stream()
                .map(subscription -> mapShop(subscription, countBranches(subscription.getTenantId())))
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
        LocalDateTime validUntil = extendByBillingCycle(LocalDateTime.now(), plan.getBillingCycle(), subscriptionCycles);

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

        recordBilling(
                tenantId,
                shopName,
                BillingActionType.ONBOARDING,
                request.getAmountPaid() != null ? request.getAmountPaid() : plan.getInitialPrice() * subscriptionCycles,
                request.getNote(),
                superAdmin.getUsername()
        );

        return mapShop(subscription, 1);
    }

    @Transactional
    public ShopSummaryResponse updateShopBlockStatus(String tenantId, ShopBlockRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        subscription.setBlocked(request.isBlocked());
        subscription.setNotes(trimToNull(request.getReason()));

        return mapShop(subscription, countBranches(subscription.getTenantId()));
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

        return ShopDetailsResponse.builder()
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
        ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        runInTenant(subscription.getTenantId(), () -> {
            User adminUser = userRepository.findByUsername(subscription.getAdminUsername())
                    .orElseGet(() -> userRepository.findFirstAdminNative()
                            .orElseThrow(() -> new ResourceNotFoundException("Tenant admin not found")));

            adminUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
            userRepository.save(adminUser);
            return null;
        });

        return mapShop(subscription, countBranches(subscription.getTenantId()));
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

        double amount = request.getAmountPaid() != null
                ? request.getAmountPaid()
                : subscription.getPlan().getRenewalPrice() * request.getCycles();
        recordBilling(subscription.getTenantId(), subscription.getShopName(), BillingActionType.RENEWAL, amount, request.getNote(), superAdmin.getUsername());

        return mapShop(subscription, countBranches(subscription.getTenantId()));
    }

    @Transactional
    public ShopSummaryResponse changePackage(String tenantId, ChangePackageRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);
        SubscriptionPlan newPlan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));

        subscription.setPlan(newPlan);
        LocalDateTime base = subscription.getValidUntil().isAfter(LocalDateTime.now())
                ? subscription.getValidUntil()
                : LocalDateTime.now();
        subscription.setValidUntil(extendByBillingCycle(base, newPlan.getBillingCycle(), 1));
        subscription.setActive(true);
        subscription.setNotes(trimToNull(request.getNote()));

        double amount = request.getAmountPaid() != null ? request.getAmountPaid() : newPlan.getInitialPrice();
        recordBilling(subscription.getTenantId(), subscription.getShopName(), BillingActionType.PLAN_CHANGE, amount, request.getNote(), superAdmin.getUsername());

        return mapShop(subscription, countBranches(subscription.getTenantId()));
    }

    @Transactional
    public ShopSummaryResponse addExtraBranches(String tenantId, AddExtraBranchesRequest request) {
        User superAdmin = ensureSuperAdmin();
        TenantSubscription subscription = getSubscriptionOrThrow(tenantId);

        subscription.setExtraBranches(subscription.getExtraBranches() + request.getExtraBranches());
        subscription.setNotes(trimToNull(request.getNote()));

        double amount = request.getAmountPaid() != null ? request.getAmountPaid() : 0.0;
        recordBilling(subscription.getTenantId(), subscription.getShopName(), BillingActionType.EXTRA_BRANCHES, amount, request.getNote(), superAdmin.getUsername());

        return mapShop(subscription, countBranches(subscription.getTenantId()));
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

    private void recordBilling(String tenantId, String shopName, BillingActionType actionType, double amount, String note, String performedBy) {
        billingRecordRepository.save(BillingRecord.builder()
                .tenantId(tenantId)
                .shopName(shopName)
                .actionType(actionType)
                .amount(amount)
                .referenceNote(trimToNull(note))
                .performedBy(performedBy)
                .build());
    }

    private long countBranches(String tenantId) {
        return runInTenant(tenantId, () -> branchRepository.countAllNative());
    }

    private ShopSummaryResponse mapShop(TenantSubscription subscription, long branchCount) {
        int baseBranches = subscription.getPlan() != null ? subscription.getPlan().getMaxBranches() : 0;
        int allowedBranches = baseBranches + subscription.getExtraBranches();

        return ShopSummaryResponse.builder()
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
