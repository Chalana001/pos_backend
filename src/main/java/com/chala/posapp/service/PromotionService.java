package com.chala.posapp.service;

import com.chala.posapp.dto.order.OrderItemRequest;
import com.chala.posapp.dto.promotion.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.promotion.engine.LineDecision;
import com.chala.posapp.promotion.engine.LineEvaluation;
import com.chala.posapp.promotion.engine.MoneyOps;
import com.chala.posapp.promotion.engine.OrderEvaluation;
import com.chala.posapp.promotion.engine.PricingLine;
import com.chala.posapp.promotion.engine.PromotionApplication;
import com.chala.posapp.promotion.engine.PromotionEvaluator;
import com.chala.posapp.promotion.engine.PromotionOrderApplication;
import com.chala.posapp.promotion.engine.PromotionSnapshot;

import com.chala.posapp.repository.*;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final BranchRepository branchRepository;
    private final CustomerRepository customerRepository;
    private final SecurityUtils securityUtils;
    private final PromotionSnapshotCache snapshotCache;
    private final PromotionGate promotionGate;
    private final PromotionCodeRepository promotionCodeRepository;
    private final PromotionLifecycleService lifecycleService;
    private final PromotionSimulationService simulationService;

    public List<PromotionResponse> list() {
        return promotionRepository.findByDeletedAtIsNullOrderByActiveDescStartAtDescIdDesc().stream()
                .map(this::mapResponse)
                .toList();
    }

    @Transactional
    public PromotionResponse create(PromotionRequest request) {
        validateRequest(request);

        Promotion promotion = Promotion.builder()
                .name(request.getName().trim())
                .scope(request.getScope())
                .discountType(request.getDiscountType())
                .discountValue(BigDecimal.valueOf(request.getDiscountValue()))
                .minBillAmount(BigDecimal.valueOf(Math.max(0, request.getMinBillAmount())))
                .maxDiscountAmount(BigDecimal.valueOf(Math.max(0, request.getMaxDiscountAmount())))
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .branchId(normalizeBranchId(request.getBranchId()))
                .active(request.isActive())
                .priority(request.getPriority())
                .marginFloorPercent(request.getMarginFloorPercent())
                .allowBelowCost(request.isAllowBelowCost())
                .effectType(request.resolvedEffectType())
                .buyQty(request.getBuyQty())
                .getQty(request.getGetQty())
                .stackingMode(request.resolvedStackingMode())
                .allowManualStacking(request.resolvedAllowManualStacking())
                .maxTotalRedemptions(positiveOrNull(request.getMaxTotalRedemptions()))
                .maxRedemptionsPerCustomer(positiveOrNull(request.getMaxRedemptionsPerCustomer()))
                .budgetAmount(positiveOrNull(request.getBudgetAmount()))
                .build();
        applyTargets(promotion, request);
        applyTiersAndSchedules(promotion, request);
        User user = securityUtils.getCurrentUser();
        lifecycleService.initialise(promotion, request.isActive(), user, this::sellingPriceOf);
        Promotion saved = promotionRepository.save(promotion);
        lifecycleService.audit(saved, PromotionAudit.Action.CREATED, user, null);
        snapshotCache.evict();
        return mapResponse(saved);
    }

    @Transactional
    public PromotionResponse update(Long id, PromotionRequest request) {
        validateRequest(request);
        Promotion promotion = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        String termsBefore = lifecycleService.termsFingerprint(promotion);

        promotion.setName(request.getName().trim());
        promotion.setScope(request.getScope());
        promotion.setDiscountType(request.getDiscountType());
        promotion.setDiscountValue(BigDecimal.valueOf(request.getDiscountValue()));
        promotion.setMinBillAmount(BigDecimal.valueOf(Math.max(0, request.getMinBillAmount())));
        promotion.setMaxDiscountAmount(BigDecimal.valueOf(Math.max(0, request.getMaxDiscountAmount())));
        promotion.setStartAt(request.getStartAt());
        promotion.setEndAt(request.getEndAt());
        promotion.setBranchId(normalizeBranchId(request.getBranchId()));
        promotion.setPriority(request.getPriority());
        promotion.setMarginFloorPercent(request.getMarginFloorPercent());
        promotion.setAllowBelowCost(request.isAllowBelowCost());
        promotion.setEffectType(request.resolvedEffectType());
        promotion.setBuyQty(request.getBuyQty());
        promotion.setGetQty(request.getGetQty());
        promotion.setStackingMode(request.resolvedStackingMode());
        promotion.setAllowManualStacking(request.resolvedAllowManualStacking());
        promotion.setMaxTotalRedemptions(positiveOrNull(request.getMaxTotalRedemptions()));
        promotion.setMaxRedemptionsPerCustomer(positiveOrNull(request.getMaxRedemptionsPerCustomer()));
        promotion.setBudgetAmount(positiveOrNull(request.getBudgetAmount()));
        promotion.getTargets().clear();
        applyTargets(promotion, request);
        promotion.getTiers().clear();
        promotion.getSchedules().clear();
        applyTiersAndSchedules(promotion, request);
        boolean termsChanged = !termsBefore.equals(lifecycleService.termsFingerprint(promotion));
        lifecycleService.onUpdated(promotion, termsChanged, request.isActive(), securityUtils.getCurrentUser(), this::sellingPriceOf);
        snapshotCache.evict();
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse updateStatus(Long id, boolean active) {
        Promotion promotion = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        lifecycleService.setActive(promotion, active, securityUtils.getCurrentUser(), this::sellingPriceOf);
        snapshotCache.evict();
        return mapResponse(promotionRepository.save(promotion));
    }

    /**
     * Retires a promotion without erasing what it did.
     *
     * <p>Past orders carry {@code promotion_id} and {@code bill_promotion_id} as plain
     * columns with no foreign key behind them, so removing the row left every sale it
     * discounted pointing at nothing — the discounts stayed on the books while the terms
     * that produced them were gone, and RPT-08 reported a null discount type for them for
     * good. Marking {@code deletedAt} keeps the row joinable for reporting; deactivating
     * alongside it means nothing downstream has to remember to check both columns.
     */
    @Transactional
    public void delete(Long id) {
        Promotion promotion = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        promotion.setDeletedAt(LocalDateTime.now());
        promotion.setActive(false);
        snapshotCache.evict();
        promotionRepository.save(promotion);
        lifecycleService.audit(promotion, PromotionAudit.Action.ARCHIVED, securityUtils.getCurrentUser(), null);
    }

    /**
     * Promotions that can price a sale at {@code branchId} right {@code now}.
     *
     * <p>The candidate set comes from a per-tenant cache; date window and branch are filtered
     * here, so the expensive part is shared across every checkout and preview while the
     * time-sensitive part is always evaluated fresh.
     */
    public List<PromotionSnapshot> activePromotionsForBranch(Long branchId, LocalDateTime now) {
        return snapshotCache.candidates().stream()
                .filter(promotion -> promotion.isRunningAt(now))
                .filter(promotion -> promotion.coversBranch(branchId))
                .toList();
    }

    /**
     * Prices one cart line. The maths lives in {@link PromotionEvaluator}; this resolves the
     * entity into a {@link PricingLine} and hands it over.
     *
     * <p>{@code cartBaseSubtotal} is the whole cart at list price, so a promotion's minimum bill
     * can be judged without circularity.
     */
    public PromotionApplication calculateBestDiscount(
            Item item,
            Long branchId,
            double unitPrice,
            int normalizedQty,
            DiscountType manualType,
            double manualValue,
            double cartBaseSubtotal,
            List<PromotionSnapshot> activePromotions
    ) {
        return evaluateLine(item, branchId, unitPrice, normalizedQty, manualType, manualValue,
                cartBaseSubtotal, activePromotions).application();
    }

    /** Same as {@link #calculateBestDiscount} but keeps the reasoning, for the preview. */
    public LineEvaluation evaluateLine(
            Item item,
            Long branchId,
            double unitPrice,
            int normalizedQty,
            DiscountType manualType,
            double manualValue,
            double cartBaseSubtotal,
            List<PromotionSnapshot> activePromotions
    ) {
        PricingLine line = PricingLine.from(item, unitPrice, normalizedQty, manualType, manualValue);
        return PromotionEvaluator.evaluateLine(line, branchId, BigDecimal.valueOf(cartBaseSubtotal), activePromotions);
    }

    /**
     * @param pricedLines        the cart with each line at its post-line-discount unit price;
     *                           bundles and cheapest-free count units from it
     * @param linesHaveExclusive a line winner was EXCLUSIVE, so bill-level promotions stand down
     */
    public PromotionOrderApplication calculateBestOrderDiscount(
            Long branchId,
            Long customerId,
            double baseTotal,
            double manualBillDiscount,
            List<PricingLine> pricedLines,
            boolean linesHaveExclusive,
            List<PromotionSnapshot> activePromotions
    ) {
        return evaluateOrder(branchId, customerId, baseTotal, manualBillDiscount,
                pricedLines, linesHaveExclusive, activePromotions).application();
    }

    public OrderEvaluation evaluateOrder(
            Long branchId,
            Long customerId,
            double baseTotal,
            double manualBillDiscount,
            List<PricingLine> pricedLines,
            boolean linesHaveExclusive,
            List<PromotionSnapshot> activePromotions
    ) {
        return PromotionEvaluator.evaluateOrder(branchId, customerId,
                BigDecimal.valueOf(baseTotal), BigDecimal.valueOf(manualBillDiscount),
                pricedLines, linesHaveExclusive, activePromotions);
    }

    public PromotionPreviewResponse preview(PromotionPreviewRequest request) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, request.getBranchId());
        LocalDateTime now = LocalDateTime.now();
        PromotionGate.Result gate = promotionGate.gate(
                activePromotionsForBranch(branchId, now), request.getPromotionCode(), request.getCustomerId(), now);
        List<PromotionSnapshot> activePromotions = gate.eligible();

        // Resolve every line first: minBillAmount is judged against the whole cart at list
        // price, so the subtotal has to be known before the first line is priced.
        List<ResolvedLine> lines = new ArrayList<>();
        double cartBaseSubtotal = 0;
        for (OrderItemRequest itemRequest : request.getItems()) {
            Item item = itemRepository.findById(itemRequest.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemRequest.getItemId()));
            int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, itemRequest.getQty(), itemRequest.getQtyUnit());
            lines.add(new ResolvedLine(item, itemRequest, normalizedQty));
            cartBaseSubtotal += MoneyOps.lineTotal(item.getItemType(),
                    BigDecimal.valueOf(itemRequest.getUnitPrice()), normalizedQty).doubleValue();
        }

        List<PromotionPreviewItemResponse> items = new ArrayList<>();
        List<PricingLine> pricedLines = new ArrayList<>();
        boolean linesHaveExclusive = false;
        for (ResolvedLine line : lines) {
            OrderItemRequest itemRequest = line.request();
            LineEvaluation evaluation = evaluateLine(
                    line.item(),
                    branchId,
                    itemRequest.getUnitPrice(),
                    line.normalizedQty(),
                    itemRequest.getDiscountType(),
                    itemRequest.getDiscountValue(),
                    cartBaseSubtotal,
                    activePromotions
            );
            PromotionApplication application = evaluation.application();
            PricingLine listPriced = PricingLine.from(line.item(), itemRequest.getUnitPrice(), line.normalizedQty(),
                    DiscountType.NONE, 0);
            pricedLines.add(listPriced.withUnitPrice(MoneyOps.unitPriceForLineDiscount(
                    listPriced.unitPrice(),
                    BigDecimal.valueOf(application.baseLineTotal()),
                    BigDecimal.valueOf(application.appliedDiscountAmount()))));
            linesHaveExclusive |= application.exclusive();
            items.add(PromotionPreviewItemResponse.builder()
                    .itemId(line.item().getId())
                    .promotionId(application.promotionId())
                    .promotionName(application.promotionName())
                    .discountType(application.discountType())
                    .discountValue(application.discountValue())
                    .manualDiscountAmount(application.manualDiscountAmount())
                    .promotionDiscountAmount(application.promotionDiscountAmount())
                    .appliedDiscountAmount(application.appliedDiscountAmount())
                    .baseLineTotal(application.baseLineTotal())
                    .finalLineTotal(application.finalLineTotal())
                    .promotionApplied(application.promotionApplied())
                    .decisions(toDecisionResponses(evaluation.decisions()))
                    .build());
        }

        double subtotalAfterLineDiscounts = items.stream()
                .mapToDouble(PromotionPreviewItemResponse::getFinalLineTotal)
                .sum();
        double promotionDiscountTotal = items.stream()
                .filter(PromotionPreviewItemResponse::isPromotionApplied)
                .mapToDouble(PromotionPreviewItemResponse::getPromotionDiscountAmount)
                .sum();
        OrderEvaluation orderEvaluation = evaluateOrder(
                branchId,
                request.getCustomerId(),
                subtotalAfterLineDiscounts,
                request.getBillDiscount(),
                pricedLines,
                linesHaveExclusive,
                activePromotions
        );
        PromotionOrderApplication orderApplication = orderEvaluation.application();
        return PromotionPreviewResponse.builder()
                .items(items)
                .promotionDiscountTotal(roundMoney(promotionDiscountTotal + (orderApplication.promotionApplied() ? orderApplication.promotionDiscountAmount() : 0.0)))
                .billPromotionId(orderApplication.promotionApplied() ? orderApplication.promotionId() : null)
                .billPromotionName(orderApplication.promotionApplied() ? orderApplication.promotionName() : null)
                .manualBillDiscountAmount(orderApplication.manualBillDiscountAmount())
                .billPromotionDiscountAmount(orderApplication.promotionApplied() ? orderApplication.promotionDiscountAmount() : 0.0)
                .appliedBillDiscountAmount(orderApplication.appliedDiscountAmount())
                .finalTotal(orderApplication.finalTotal())
                .billPromotionApplied(orderApplication.promotionApplied())
                .billDecisions(toDecisionResponses(concat(gate.excluded(), orderEvaluation.decisions())))
                .codeStatus(gate.codeStatus())
                .build();
    }

    private static List<LineDecision> concat(List<LineDecision> first, List<LineDecision> second) {
        List<LineDecision> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private BigDecimal sellingPriceOf(Long itemId) {
        return itemRepository.findById(itemId).map(Item::getSellingPrice).orElse(null);
    }

    public PromotionSettingsDto settings() {
        return lifecycleService.toDto(lifecycleService.settings());
    }

    @Transactional
    public PromotionSettingsDto updateSettings(PromotionSettingsDto request) {
        return lifecycleService.updateSettings(request, securityUtils.getCurrentUser());
    }

    @Transactional
    public PromotionResponse submit(Long id) {
        Promotion promotion = requireLivePromotion(id);
        lifecycleService.submit(promotion, securityUtils.getCurrentUser(), this::sellingPriceOf);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse approve(Long id, String note) {
        Promotion promotion = requireLivePromotion(id);
        lifecycleService.approve(promotion, securityUtils.getCurrentUser(), note);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse reject(Long id, String note) {
        Promotion promotion = requireLivePromotion(id);
        lifecycleService.reject(promotion, securityUtils.getCurrentUser(), note);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse pause(Long id, String note) {
        Promotion promotion = requireLivePromotion(id);
        lifecycleService.pause(promotion, securityUtils.getCurrentUser(), note);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse resume(Long id) {
        Promotion promotion = requireLivePromotion(id);
        lifecycleService.resume(promotion, securityUtils.getCurrentUser(), this::sellingPriceOf);
        return mapResponse(promotionRepository.save(promotion));
    }

    public List<PromotionAuditDto> auditTrail(Long id) {
        requireLivePromotion(id);
        return lifecycleService.auditTrail(id);
    }

    public PromotionSimulationResponse simulate(PromotionSimulationRequest request) {
        validateRequest(request.getPromotion());
        return simulationService.simulate(request);
    }

    public PromotionCheckResponse check(PromotionRequest request, Long excludeId) {
        validateRequest(request);
        return simulationService.check(request, excludeId);
    }

    /** "Would this code work right now?" — for the till, without consuming anything. */
    public CodeCheckResponse checkCode(CodeCheckRequest request) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, request.getBranchId());
        LocalDateTime now = LocalDateTime.now();
        PromotionGate.Result gate = promotionGate.gate(
                activePromotionsForBranch(branchId, now), request.getCode(), request.getCustomerId(), now);
        return gate.codeStatus();
    }

    public List<PromotionCodeDto> listCodes(Long promotionId) {
        requireLivePromotion(promotionId);
        return promotionCodeRepository.findByPromotionIdOrderByIdAsc(promotionId).stream()
                .map(PromotionCodeDto::from)
                .toList();
    }

    /**
     * Mints codes. A named code is created as given; otherwise {@code count} random ones under
     * the prefix. Random codes avoid 0/O and 1/I because they are read aloud and typed by hand.
     */
    @Transactional
    public List<PromotionCodeDto> generateCodes(Long promotionId, GenerateCodesRequest request) {
        Promotion promotion = requireLivePromotion(promotionId);
        if (request.getMaxRedemptions() != null && request.getMaxRedemptions() <= 0) {
            throw new BadRequestException("Max uses must be greater than 0, or empty for unlimited");
        }
        if (request.getPerCustomerLimit() != null && request.getPerCustomerLimit() <= 0) {
            throw new BadRequestException("Per-customer limit must be greater than 0, or empty for unlimited");
        }
        if (request.getValidFrom() != null && request.getValidTo() != null
                && !request.getValidTo().isAfter(request.getValidFrom())) {
            throw new BadRequestException("Code end must be after its start");
        }

        String batchId = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<PromotionCode> created = new ArrayList<>();

        if (request.getCode() != null && !request.getCode().isBlank()) {
            String code = request.getCode().trim().toUpperCase();
            if (!code.matches("[A-Z0-9-]{3,40}")) {
                throw new BadRequestException("A code is 3-40 letters, digits or dashes");
            }
            if (promotionCodeRepository.existsByCodeIgnoreCase(code)) {
                throw new BadRequestException("Code " + code + " already exists");
            }
            created.add(newCode(promotion, code, request, batchId));
        } else {
            String prefix = request.getPrefix() == null ? "" : request.getPrefix().trim().toUpperCase();
            if (!prefix.matches("[A-Z0-9]{0,8}")) {
                throw new BadRequestException("A prefix is up to 8 letters or digits");
            }
            java.security.SecureRandom random = new java.security.SecureRandom();
            java.util.Set<String> minted = new java.util.HashSet<>();
            int attempts = 0;
            while (created.size() < request.getCount()) {
                if (++attempts > request.getCount() * 20) {
                    throw new BadRequestException("Could not generate enough unique codes; try a different prefix");
                }
                String code = prefix + randomCode(random, 8);
                if (!minted.add(code) || promotionCodeRepository.existsByCodeIgnoreCase(code)) {
                    continue;
                }
                created.add(newCode(promotion, code, request, batchId));
            }
        }

        promotion.getCodes().addAll(created);
        promotionRepository.save(promotion);
        return created.stream().map(PromotionCodeDto::from).toList();
    }

    @Transactional
    public PromotionCodeDto setCodeActive(Long promotionId, Long codeId, boolean active) {
        requireLivePromotion(promotionId);
        PromotionCode code = promotionCodeRepository.findById(codeId)
                .filter(c -> Objects.equals(c.getPromotion().getId(), promotionId))
                .orElseThrow(() -> new ResourceNotFoundException("Code not found"));
        code.setActive(active);
        return PromotionCodeDto.from(promotionCodeRepository.save(code));
    }

    private Promotion requireLivePromotion(Long promotionId) {
        return promotionRepository.findByIdAndDeletedAtIsNull(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
    }

    private PromotionCode newCode(Promotion promotion, String code, GenerateCodesRequest request, String batchId) {
        PromotionCode.CodeType type = request.getCodeType() == null ? PromotionCode.CodeType.PUBLIC : request.getCodeType();
        Integer maxRedemptions = request.getMaxRedemptions();
        if (type == PromotionCode.CodeType.SINGLE_USE && maxRedemptions == null) {
            maxRedemptions = 1;
        }
        return PromotionCode.builder()
                .promotion(promotion)
                .code(code)
                .codeType(type)
                .maxRedemptions(maxRedemptions)
                .perCustomerLimit(request.getPerCustomerLimit())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .active(true)
                .batchId(batchId)
                .build();
    }

    /** No 0/O or 1/I: these get read over a counter and typed on a till. */
    private static String randomCode(java.security.SecureRandom random, int length) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private List<PromotionDecisionResponse> toDecisionResponses(List<LineDecision> decisions) {
        return decisions.stream()
                .map(decision -> PromotionDecisionResponse.builder()
                        .promotionId(decision.promotionId())
                        .promotionName(decision.promotionName())
                        .outcome(decision.outcome().name())
                        .discount(decision.discount().doubleValue())
                        .build())
                .toList();
    }

    /** One preview line with its item and quantity already resolved, so the cart is only walked once. */
    private record ResolvedLine(Item item, OrderItemRequest request, int normalizedQty) {
    }

    private void validateRequest(PromotionRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Promotion name is required");
        }
        if (request.getScope() == null) {
            throw new BadRequestException("Promotion scope is required");
        }
        validateEffect(request);
        validateSchedules(request);
        if (request.getMaxTotalRedemptions() != null && request.getMaxTotalRedemptions() < 0) {
            throw new BadRequestException("Redemption limit cannot be negative");
        }
        if (request.getMaxRedemptionsPerCustomer() != null && request.getMaxRedemptionsPerCustomer() < 0) {
            throw new BadRequestException("Per-customer limit cannot be negative");
        }
        if (request.getBudgetAmount() != null && request.getBudgetAmount().signum() < 0) {
            throw new BadRequestException("Budget cannot be negative");
        }
        if (request.getStartAt() == null || request.getEndAt() == null) {
            throw new BadRequestException("Promotion start and end dates are required");
        }
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            throw new BadRequestException("Promotion end date must be after start date");
        }
        normalizeBranchId(request.getBranchId());
        validateTargets(request);
    }

    /**
     * Each mechanic has its own required fields, and the message names the field a shop owner
     * sees on screen rather than the column.
     */
    private void validateEffect(PromotionRequest request) {
        PromotionEffectType effect = request.resolvedEffectType();
        boolean lineScope = request.getScope() == PromotionScope.ITEM || request.getScope() == PromotionScope.CATEGORY;

        switch (effect) {
            case DISCOUNT -> {
                if (request.getDiscountType() == null || request.getDiscountType() == DiscountType.NONE) {
                    throw new BadRequestException("Promotion discount type must be PERCENT or FIXED");
                }
                if (request.getDiscountValue() <= 0) {
                    throw new BadRequestException("Promotion discount value must be greater than 0");
                }
                if (request.getDiscountType() == DiscountType.PERCENT && request.getDiscountValue() > 100) {
                    throw new BadRequestException("Percent discount cannot exceed 100");
                }
            }
            case FIXED_PRICE -> {
                if (!lineScope) {
                    throw new BadRequestException("A fixed price applies to items or categories");
                }
                if (request.getDiscountValue() <= 0) {
                    throw new BadRequestException("Fixed price must be greater than 0");
                }
            }
            case BUY_X_GET_Y_FREE -> {
                if (!lineScope) {
                    throw new BadRequestException("Buy X get Y free applies to items or categories");
                }
                if (request.getBuyQty() == null || request.getBuyQty().signum() <= 0) {
                    throw new BadRequestException("Buy quantity must be greater than 0");
                }
                if (request.getGetQty() == null || request.getGetQty().signum() <= 0) {
                    throw new BadRequestException("Free quantity must be greater than 0");
                }
            }
            case TIERED -> {
                List<PromotionTierDto> tiers = request.getTiers() == null ? List.of() : request.getTiers();
                if (tiers.isEmpty()) {
                    throw new BadRequestException("Add at least one tier");
                }
                for (PromotionTierDto tier : tiers) {
                    boolean hasQty = tier.getMinQty() != null && tier.getMinQty().signum() > 0;
                    boolean hasAmount = tier.getMinAmount() != null && tier.getMinAmount().signum() > 0;
                    if (lineScope && !hasQty) {
                        throw new BadRequestException("Each tier needs a minimum quantity");
                    }
                    if (!lineScope && !hasAmount) {
                        throw new BadRequestException("Each tier needs a minimum bill amount");
                    }
                    if (tier.getDiscountType() == null || tier.getDiscountType() == DiscountType.NONE) {
                        throw new BadRequestException("Each tier needs a discount type");
                    }
                    if (tier.getDiscountValue() == null || tier.getDiscountValue().signum() <= 0) {
                        throw new BadRequestException("Each tier needs a discount greater than 0");
                    }
                    if (tier.getDiscountType() == DiscountType.PERCENT && tier.getDiscountValue().doubleValue() > 100) {
                        throw new BadRequestException("A tier's percent discount cannot exceed 100");
                    }
                }
                long distinct = tiers.stream()
                        .map(tier -> lineScope ? tier.getMinQty() : tier.getMinAmount())
                        .map(BigDecimal::stripTrailingZeros)
                        .distinct().count();
                if (distinct != tiers.size()) {
                    throw new BadRequestException("Two tiers share the same threshold");
                }
            }
            case BUNDLE -> {
                if (request.getBuyQty() == null || request.getBuyQty().compareTo(BigDecimal.valueOf(2)) < 0) {
                    throw new BadRequestException("A bundle needs at least 2 items");
                }
                if (request.getDiscountValue() <= 0) {
                    throw new BadRequestException("Bundle price must be greater than 0");
                }
            }
            case CHEAPEST_FREE -> {
                if (request.getBuyQty() == null || request.getBuyQty().compareTo(BigDecimal.valueOf(2)) < 0) {
                    throw new BadRequestException("Cheapest-free needs a group of at least 2 items");
                }
            }
        }
    }

    private void validateSchedules(PromotionRequest request) {
        if (request.getSchedules() == null) {
            return;
        }
        for (PromotionScheduleDto schedule : request.getSchedules()) {
            if (schedule.getDaysOfWeek() < 0 || schedule.getDaysOfWeek() > 127) {
                throw new BadRequestException("Schedule days are out of range");
            }
            if ((schedule.getStartTime() == null) != (schedule.getEndTime() == null)) {
                throw new BadRequestException("A schedule needs both a start and an end time, or neither");
            }
        }
    }

    private void applyTiersAndSchedules(Promotion promotion, PromotionRequest request) {
        if (request.resolvedEffectType() == PromotionEffectType.TIERED && request.getTiers() != null) {
            int order = 0;
            for (PromotionTierDto tier : request.getTiers()) {
                promotion.getTiers().add(PromotionTier.builder()
                        .promotion(promotion)
                        .minQty(tier.getMinQty())
                        .minAmount(tier.getMinAmount())
                        .discountType(tier.getDiscountType())
                        .discountValue(tier.getDiscountValue())
                        .sortOrder(order++)
                        .build());
            }
        }
        if (request.getSchedules() != null) {
            for (PromotionScheduleDto schedule : request.getSchedules()) {
                promotion.getSchedules().add(PromotionSchedule.builder()
                        .promotion(promotion)
                        .daysOfWeek(schedule.getDaysOfWeek())
                        .startTime(schedule.getStartTime())
                        .endTime(schedule.getEndTime())
                        .build());
            }
        }
    }

    private void validateTargets(PromotionRequest request) {
        if (request.getScope() == PromotionScope.ITEM) {
            validateItemLines(request);
            return;
        }

        if (request.getScope() == PromotionScope.BILL) {
            return;
        }

        if (request.getScope() == PromotionScope.CUSTOMER) {
            List<Long> customerIds = distinctIds(request.getCustomerIds());
            if (customerIds.isEmpty()) {
                throw new BadRequestException("At least one customer is required for customer promotion");
            }
            if (customerRepository.findAllById(customerIds).size() != customerIds.size()) {
                throw new ResourceNotFoundException("One or more promotion customers not found");
            }
            return;
        }

        List<Long> categoryIds = distinctIds(request.getCategoryIds());
        List<Long> subCategoryIds = distinctIds(request.getSubCategoryIds());
        if (categoryIds.isEmpty() && subCategoryIds.isEmpty()) {
            throw new BadRequestException("At least one category or sub category is required for category promotion");
        }
        if (!categoryIds.isEmpty() && categoryRepository.findAllById(categoryIds).size() != categoryIds.size()) {
            throw new ResourceNotFoundException("One or more promotion categories not found");
        }
        if (!subCategoryIds.isEmpty() && subCategoryRepository.findAllById(subCategoryIds).size() != subCategoryIds.size()) {
            throw new ResourceNotFoundException("One or more promotion sub categories not found");
        }
    }

    /**
     * Every campaign with what it actually did, retired ones included.
     *
     * <p>Totals count line-level and bill-level discounts together. RPT-08 reads only
     * {@code orders.bill_promotion_id}, so a shop running item promotions sees an empty
     * report there and concludes nothing fired; this is the same question answered over
     * both halves.
     */
    public List<PromotionHistoryResponse> history(Long branchId, LocalDate from, LocalDate to) {
        LocalDateTime fromDate = (from == null ? LocalDate.now().minusYears(1) : from).atStartOfDay();
        LocalDateTime toDate = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        long branchFilter = branchId == null || branchId <= 0 ? 0L : branchId;

        Map<Long, long[]> counts = new LinkedHashMap<>();
        Map<Long, double[]> money = new LinkedHashMap<>();
        for (Object[] row : promotionRepository.promotionTotalsRaw(branchFilter, fromDate, toDate)) {
            Long promotionId = toLong(row[0]);
            if (promotionId == null) {
                continue;
            }
            counts.put(promotionId, new long[]{toLong(row[1]) == null ? 0 : toLong(row[1])});
            money.put(promotionId, new double[]{toDouble(row[2]), toDouble(row[3])});
        }

        LocalDateTime now = LocalDateTime.now();
        return promotionRepository.findAllByOrderByStartAtDescIdDesc().stream()
                .map(promotion -> {
                    long[] applied = counts.get(promotion.getId());
                    double[] amounts = money.get(promotion.getId());
                    return PromotionHistoryResponse.builder()
                            .id(promotion.getId())
                            .name(promotion.getName())
                            .scope(promotion.getScope())
                            .discountType(promotion.getDiscountType())
                            .discountValue(promotion.getDiscountValue().doubleValue())
                            .startAt(promotion.getStartAt())
                            .endAt(promotion.getEndAt())
                            .branchId(promotion.getBranchId())
                            .active(promotion.isActive())
                            .deleted(promotion.getDeletedAt() != null)
                            .status(lifecycleStatus(promotion, now))
                            .targetCount(promotion.getTargets().size())
                            .timesApplied(applied == null ? 0 : applied[0])
                            .totalDiscountGiven(amounts == null ? 0 : roundMoney(amounts[0]))
                            .totalRevenue(amounts == null ? 0 : roundMoney(amounts[1]))
                            .build();
                })
                .toList();
    }

    public List<PromotionRedemptionResponse> redemptions(
            Long promotionId, Long branchId, LocalDate from, LocalDate to, int page, int size) {
        LocalDateTime fromDate = (from == null ? LocalDate.now().minusMonths(3) : from).atStartOfDay();
        LocalDateTime toDate = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        int pageSize = Math.min(Math.max(size, 1), 500);
        int offset = Math.max(page, 0) * pageSize;

        return promotionRepository.promotionRedemptionsRaw(
                        promotionId == null || promotionId <= 0 ? 0L : promotionId,
                        branchId == null || branchId <= 0 ? 0L : branchId,
                        fromDate, toDate, pageSize, offset)
                .stream()
                .map(row -> PromotionRedemptionResponse.builder()
                        .orderId(toLong(row[0]))
                        .invoiceNo(row[1] == null ? null : row[1].toString())
                        .soldAt(toDateTime(row[2]))
                        .branchId(toLong(row[3]))
                        .promotionId(toLong(row[4]))
                        .promotionName(row[5] == null ? null : row[5].toString())
                        .level(row[6] == null ? null : row[6].toString())
                        .itemId(toLong(row[7]))
                        .itemName(row[8] == null ? null : row[8].toString())
                        .discountAmount(roundMoney(toDouble(row[9])))
                        .orderTotal(roundMoney(toDouble(row[10])))
                        .build())
                .toList();
    }

    /**
     * What the list should call this promotion right now.
     *
     * <p>Derived rather than stored, because the stored {@code active} flag says nothing about
     * the dates: a campaign that ended in March is still {@code active = true} and rendered as
     * a green "Active" pill today.
     */
    private String lifecycleStatus(Promotion promotion, LocalDateTime now) {
        if (promotion.getDeletedAt() != null) {
            return "ARCHIVED";
        }
        if (promotion.getStatus() == PromotionStatus.DRAFT) {
            return "DRAFT";
        }
        if (promotion.getStatus() == PromotionStatus.PENDING_APPROVAL) {
            return "PENDING_APPROVAL";
        }
        if (promotion.getEndAt() != null && promotion.getEndAt().isBefore(now)) {
            return "ENDED";
        }
        if (!promotion.isActive()) {
            return "PAUSED";
        }
        if (promotion.isExhausted()) {
            return "EXHAUSTED";
        }
        if (promotion.getStartAt() != null && promotion.getStartAt().isAfter(now)) {
            return "SCHEDULED";
        }
        return "LIVE";
    }

    private Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private double toDouble(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return null;
    }

    /**
     * Prices a proposed item list without saving it, so the builder can show margins and
     * warnings while the operator types.
     *
     * <p>Deliberately never throws on a bad price: this answers "what would this do", and a
     * half-finished price list is the normal state of the screen calling it. The refusals live
     * in {@link #validateItemLines} on the save path; this reports the same conditions as
     * statuses so the two cannot disagree about what counts as below cost.
     */
    public PromotionPriceCheckResponse priceCheck(PromotionPriceCheckRequest request) {
        List<PromotionItemLine> lines = request.getItems() == null ? List.<PromotionItemLine>of() : request.getItems();
        List<Long> itemIds = lines.stream()
                .map(PromotionItemLine::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity(), (a, b) -> a));

        List<PromotionPriceCheckResponse.Line> results = new ArrayList<>();
        int belowCost = 0;
        int belowFloor = 0;
        double discountPercentSum = 0;
        double maxLineDiscount = 0;

        for (PromotionItemLine line : lines) {
            Item item = line.getId() == null ? null : itemsById.get(line.getId());
            if (item == null) {
                continue;
            }
            double normalPrice = item.getSellingPrice() == null ? 0 : item.getSellingPrice().doubleValue();
            double cost = item.getCostPrice() == null ? 0 : item.getCostPrice().doubleValue();

            double offerPrice = resolveCheckPrice(line, request, normalPrice);
            double discountAmount = roundMoney(Math.max(0, normalPrice - offerPrice));
            double discountPercent = normalPrice > 0 ? roundMoney((discountAmount / normalPrice) * 100.0) : 0;
            double marginPercent = offerPrice > 0 ? roundMoney(((offerPrice - cost) / offerPrice) * 100.0) : 0;

            String status = "OK";
            String message = null;
            if (line.getOfferPrice() != null && normalPrice > 0 && line.getOfferPrice().doubleValue() > normalPrice) {
                status = "ABOVE_NORMAL_PRICE";
                message = "Offer price is above the normal price";
            } else if (item.getCostPrice() != null && offerPrice < cost) {
                status = "BELOW_COST";
                message = String.format("Sells at %.2f against a cost of %.2f", offerPrice, cost);
                belowCost++;
            } else if (request.getMarginFloorPercent() != null
                    && marginPercent < request.getMarginFloorPercent().doubleValue()) {
                status = "LOW_MARGIN";
                message = String.format("Margin %.1f%% is below the %.1f%% floor",
                        marginPercent, request.getMarginFloorPercent().doubleValue());
                belowFloor++;
            }

            discountPercentSum += discountPercent;
            maxLineDiscount = Math.max(maxLineDiscount, discountAmount);

            results.add(PromotionPriceCheckResponse.Line.builder()
                    .itemId(item.getId())
                    .itemName(item.getName())
                    .barcode(item.getBarcode())
                    .normalPrice(bd(normalPrice))
                    .costPrice(item.getCostPrice())
                    .offerPrice(bd(offerPrice))
                    .discountAmount(bd(discountAmount))
                    .discountPercent(bd(discountPercent))
                    .marginPercent(bd(marginPercent))
                    .status(status)
                    .message(message)
                    .build());
        }

        return PromotionPriceCheckResponse.builder()
                .items(results)
                .belowCostCount(belowCost)
                .belowFloorCount(belowFloor)
                .averageDiscountPercent(bd(results.isEmpty() ? 0 : roundMoney(discountPercentSum / results.size())))
                .maxLineDiscount(bd(maxLineDiscount))
                .build();
    }

    private double resolveCheckPrice(PromotionItemLine line, PromotionPriceCheckRequest request, double normalPrice) {
        if (line.getOfferPrice() != null) {
            return Math.max(0, line.getOfferPrice().doubleValue());
        }
        if (line.getDiscountType() != null && line.getDiscountType() != DiscountType.NONE
                && line.getDiscountValue() != null) {
            return calculateFinalUnitPrice(normalPrice, line.getDiscountType(), line.getDiscountValue().doubleValue());
        }
        return calculateFinalUnitPrice(normalPrice, request.getDiscountType(), request.getDiscountValue());
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(roundMoney(value));
    }

    /**
     * Copies a promotion as a fresh inactive one.
     *
     * <p>"Same as last Christmas, with new dates" is how a seasonal campaign is actually
     * created, and retyping a forty-line price list to get there is where prices get mistyped.
     * The copy comes back inactive so the operator sets the dates before it can price anything.
     */
    @Transactional
    public PromotionResponse duplicate(Long id) {
        Promotion source = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));

        Promotion copy = Promotion.builder()
                .name(nextCopyName(source.getName()))
                .scope(source.getScope())
                .discountType(source.getDiscountType())
                .discountValue(source.getDiscountValue())
                .minBillAmount(source.getMinBillAmount())
                .maxDiscountAmount(source.getMaxDiscountAmount())
                .startAt(source.getStartAt())
                .endAt(source.getEndAt())
                .branchId(source.getBranchId())
                .active(false)
                .status(PromotionStatus.DRAFT)
                .priority(source.getPriority())
                .marginFloorPercent(source.getMarginFloorPercent())
                .allowBelowCost(source.isAllowBelowCost())
                .effectType(source.getEffectType())
                .buyQty(source.getBuyQty())
                .getQty(source.getGetQty())
                .stackingMode(source.getStackingMode())
                .allowManualStacking(source.isAllowManualStacking())
                .maxTotalRedemptions(source.getMaxTotalRedemptions())
                .maxRedemptionsPerCustomer(source.getMaxRedemptionsPerCustomer())
                .budgetAmount(source.getBudgetAmount())
                .build();
        // Codes are deliberately not copied: they are unique, and a duplicate campaign wants
        // its own batch, not a second promotion answering to last year's codes.

        source.getTargets().forEach(target -> copy.getTargets().add(PromotionTarget.builder()
                .promotion(copy)
                .itemId(target.getItemId())
                .categoryId(target.getCategoryId())
                .subCategoryId(target.getSubCategoryId())
                .customerId(target.getCustomerId())
                .offerPrice(target.getOfferPrice())
                .discountType(target.getDiscountType())
                .discountValue(target.getDiscountValue())
                .build()));

        source.getTiers().forEach(tier -> copy.getTiers().add(PromotionTier.builder()
                .promotion(copy)
                .minQty(tier.getMinQty())
                .minAmount(tier.getMinAmount())
                .discountType(tier.getDiscountType())
                .discountValue(tier.getDiscountValue())
                .sortOrder(tier.getSortOrder())
                .build()));
        source.getSchedules().forEach(schedule -> copy.getSchedules().add(PromotionSchedule.builder()
                .promotion(copy)
                .daysOfWeek(schedule.getDaysOfWeek())
                .startTime(schedule.getStartTime())
                .endTime(schedule.getEndTime())
                .build()));
        User duplicator = securityUtils.getCurrentUser();
        copy.setCreatedBy(duplicator == null ? null : duplicator.getId());
        copy.setUpdatedBy(copy.getCreatedBy());
        Promotion savedCopy = promotionRepository.save(copy);
        lifecycleService.audit(savedCopy, PromotionAudit.Action.DUPLICATED, duplicator, "Copied from '" + source.getName() + "'");
        snapshotCache.evict();
        return mapResponse(savedCopy);
    }

    private String nextCopyName(String name) {
        String base = (name == null ? "Promotion" : name) + " (copy)";
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    /**
     * Checks the item list of an ITEM promotion, including any per-item prices on it.
     *
     * <p>Two refusals matter here and both are about a typo rather than a policy. An offer
     * price above the item's own selling price is a price rise, which is never the intent. An
     * offer price below cost is how 39.90 typed for 399.00 shows up — legitimate as a loss
     * leader, which is what {@code allowBelowCost} is for, and a mistake otherwise. Both name
     * the item and the numbers, because "invalid request" on a forty-line price list is
     * useless.
     */
    private void validateItemLines(PromotionRequest request) {
        List<PromotionItemLine> lines = distinctItemLines(request);
        if (lines.isEmpty()) {
            throw new BadRequestException("At least one item is required for item promotion");
        }

        List<Long> itemIds = lines.stream().map(PromotionItemLine::getId).toList();
        Map<Long, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity(), (a, b) -> a));
        if (itemsById.size() != itemIds.size()) {
            throw new ResourceNotFoundException("One or more promotion items not found");
        }

        for (PromotionItemLine line : lines) {
            Item item = itemsById.get(line.getId());
            if (line.getDiscountType() == DiscountType.PERCENT
                    && line.getDiscountValue() != null
                    && line.getDiscountValue().doubleValue() > 100) {
                throw new BadRequestException("Percent discount cannot exceed 100 for " + item.getName());
            }
            if (line.getOfferPrice() == null) {
                continue;
            }

            double offerPrice = line.getOfferPrice().doubleValue();
            if (offerPrice < 0) {
                throw new BadRequestException("Offer price cannot be negative for " + item.getName());
            }

            BigDecimal sellingPrice = item.getSellingPrice();
            if (sellingPrice != null && offerPrice > sellingPrice.doubleValue()) {
                throw new BadRequestException(String.format(
                        "Offer price %.2f is above the normal price %.2f for %s",
                        offerPrice, sellingPrice.doubleValue(), item.getName()));
            }

            BigDecimal costPrice = item.getCostPrice();
            if (!request.isAllowBelowCost() && costPrice != null && offerPrice < costPrice.doubleValue()) {
                throw new BadRequestException(String.format(
                        "Offer price %.2f is below the %.2f cost of %s. Allow below-cost pricing if this is deliberate.",
                        offerPrice, costPrice.doubleValue(), item.getName()));
            }
        }
    }

    /** Item lines with blanks and duplicates removed, whichever request shape the client used. */
    private List<PromotionItemLine> distinctItemLines(PromotionRequest request) {
        Map<Long, PromotionItemLine> byId = new LinkedHashMap<>();
        for (PromotionItemLine line : request.resolvedItemLines()) {
            if (line == null || line.getId() == null || line.getId() <= 0) {
                continue;
            }
            byId.putIfAbsent(line.getId(), line);
        }
        return List.copyOf(byId.values());
    }

    private void applyTargets(Promotion promotion, PromotionRequest request) {
        if (promotion.getScope() == PromotionScope.ITEM) {
            distinctItemLines(request).forEach(line ->
                    promotion.getTargets().add(PromotionTarget.builder()
                            .promotion(promotion)
                            .itemId(line.getId())
                            .offerPrice(line.getOfferPrice())
                            .discountType(line.getDiscountType())
                            .discountValue(line.getDiscountValue())
                            .build()));
            return;
        }

        if (promotion.getScope() == PromotionScope.BILL) {
            return;
        }

        if (promotion.getScope() == PromotionScope.CUSTOMER) {
            distinctIds(request.getCustomerIds()).forEach(customerId ->
                    promotion.getTargets().add(PromotionTarget.builder()
                            .promotion(promotion)
                            .customerId(customerId)
                            .build()));
            return;
        }

        distinctIds(request.getCategoryIds()).forEach(categoryId ->
                promotion.getTargets().add(PromotionTarget.builder()
                        .promotion(promotion)
                        .categoryId(categoryId)
                        .build()));
        distinctIds(request.getSubCategoryIds()).forEach(subCategoryId ->
                promotion.getTargets().add(PromotionTarget.builder()
                        .promotion(promotion)
                        .subCategoryId(subCategoryId)
                        .build()));
    }

    private Long normalizeBranchId(Long branchId) {
        if (branchId == null || branchId <= 0) {
            return null;
        }
        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found");
        }
        return branchId;
    }

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() — use SecurityUtils instead

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.CASHIER || user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null) {
                throw new BadRequestException("User branch not assigned");
            }
            if (requestedBranchId != null && requestedBranchId > 0 && !Objects.equals(user.getBranchId(), requestedBranchId)) {
                throw new BadRequestException("Cannot preview promotions for another branch");
            }
            return user.getBranchId();
        }
        if (requestedBranchId == null || requestedBranchId <= 0) {
            throw new BadRequestException("BranchId required");
        }
        if (!branchRepository.existsById(requestedBranchId)) {
            throw new ResourceNotFoundException("Branch not found");
        }
        return requestedBranchId;
    }

    private PromotionResponse mapResponse(Promotion promotion) {
        return PromotionResponse.builder()
                .id(promotion.getId())
                .name(promotion.getName())
                .scope(promotion.getScope())
                .discountType(promotion.getDiscountType())
                .discountValue(promotion.getDiscountValue().doubleValue())
                .minBillAmount(promotion.getMinBillAmount().doubleValue())
                .maxDiscountAmount(promotion.getMaxDiscountAmount().doubleValue())
                .startAt(promotion.getStartAt())
                .endAt(promotion.getEndAt())
                .branchId(promotion.getBranchId())
                .active(promotion.isActive())
                .priority(promotion.getPriority())
                .marginFloorPercent(promotion.getMarginFloorPercent())
                .allowBelowCost(promotion.isAllowBelowCost())
                .effectType(promotion.getEffectType())
                .buyQty(promotion.getBuyQty())
                .getQty(promotion.getGetQty())
                .stackingMode(promotion.getStackingMode())
                .allowManualStacking(promotion.isAllowManualStacking())
                .maxTotalRedemptions(promotion.getMaxTotalRedemptions())
                .maxRedemptionsPerCustomer(promotion.getMaxRedemptionsPerCustomer())
                .budgetAmount(promotion.getBudgetAmount())
                .timesRedeemed(promotion.getTimesRedeemed())
                .budgetConsumed(promotion.getBudgetConsumed() == null ? BigDecimal.ZERO : promotion.getBudgetConsumed())
                .codeCount(promotion.getCodes() == null ? 0 : promotion.getCodes().size())
                .exhausted(promotion.isExhausted())
                .status(promotion.getStatus())
                .lifecycle(lifecycleStatus(promotion, LocalDateTime.now()))
                .createdBy(promotion.getCreatedBy())
                .updatedBy(promotion.getUpdatedBy())
                .submittedBy(promotion.getSubmittedBy())
                .approvedBy(promotion.getApprovedBy())
                .approvedAt(promotion.getApprovedAt())
                .approvalNote(promotion.getApprovalNote())
                .tiers(promotion.getTiers().stream()
                        .map(tier -> PromotionTierDto.builder()
                                .minQty(tier.getMinQty())
                                .minAmount(tier.getMinAmount())
                                .discountType(tier.getDiscountType())
                                .discountValue(tier.getDiscountValue())
                                .build())
                        .toList())
                .schedules(promotion.getSchedules().stream()
                        .map(schedule -> PromotionScheduleDto.builder()
                                .daysOfWeek(schedule.getDaysOfWeek())
                                .startTime(schedule.getStartTime())
                                .endTime(schedule.getEndTime())
                                .build())
                        .toList())
                .itemIds(promotion.getTargets().stream().map(PromotionTarget::getItemId).filter(Objects::nonNull).toList())
                .items(promotion.getTargets().stream()
                        .filter(target -> target.getItemId() != null)
                        .map(target -> PromotionItemLine.builder()
                                .id(target.getItemId())
                                .offerPrice(target.getOfferPrice())
                                .discountType(target.getDiscountType())
                                .discountValue(target.getDiscountValue())
                                .build())
                        .toList())
                .categoryIds(promotion.getTargets().stream().map(PromotionTarget::getCategoryId).filter(Objects::nonNull).toList())
                .subCategoryIds(promotion.getTargets().stream().map(PromotionTarget::getSubCategoryId).filter(Objects::nonNull).toList())
                .customerIds(promotion.getTargets().stream().map(PromotionTarget::getCustomerId).filter(Objects::nonNull).toList())
                .build();
    }

    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    private double calculateFinalUnitPrice(double unitPrice, DiscountType type, double value) {
        if (type == null || type == DiscountType.NONE) return unitPrice;
        if (type == DiscountType.PERCENT) {
            double safeValue = Math.max(0, Math.min(100, value));
            return unitPrice - (unitPrice * (safeValue / 100.0));
        }
        double safeValue = Math.max(0, Math.min(unitPrice, value));
        return unitPrice - safeValue;
    }

    private double roundMoney(double value) {
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
