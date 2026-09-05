package com.chala.posapp.service;

import com.chala.posapp.dto.order.OrderItemRequest;
import com.chala.posapp.dto.promotion.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
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
                .discountValue(request.getDiscountValue())
                .minBillAmount(Math.max(0, request.getMinBillAmount()))
                .maxDiscountAmount(Math.max(0, request.getMaxDiscountAmount()))
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .branchId(normalizeBranchId(request.getBranchId()))
                .active(request.isActive())
                .priority(request.getPriority())
                .marginFloorPercent(request.getMarginFloorPercent())
                .allowBelowCost(request.isAllowBelowCost())
                .build();
        applyTargets(promotion, request);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse update(Long id, PromotionRequest request) {
        validateRequest(request);
        Promotion promotion = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));

        promotion.setName(request.getName().trim());
        promotion.setScope(request.getScope());
        promotion.setDiscountType(request.getDiscountType());
        promotion.setDiscountValue(request.getDiscountValue());
        promotion.setMinBillAmount(Math.max(0, request.getMinBillAmount()));
        promotion.setMaxDiscountAmount(Math.max(0, request.getMaxDiscountAmount()));
        promotion.setStartAt(request.getStartAt());
        promotion.setEndAt(request.getEndAt());
        promotion.setBranchId(normalizeBranchId(request.getBranchId()));
        promotion.setActive(request.isActive());
        promotion.setPriority(request.getPriority());
        promotion.setMarginFloorPercent(request.getMarginFloorPercent());
        promotion.setAllowBelowCost(request.isAllowBelowCost());
        promotion.getTargets().clear();
        applyTargets(promotion, request);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse updateStatus(Long id, boolean active) {
        Promotion promotion = promotionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        promotion.setActive(active);
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
        promotionRepository.save(promotion);
    }

    public List<Promotion> activePromotionsForBranch(Long branchId, LocalDateTime now) {
        return promotionRepository
                .findByActiveTrueAndDeletedAtIsNullAndStartAtLessThanEqualAndEndAtGreaterThanEqualOrderByPriorityDescIdDesc(now, now)
                .stream()
                .filter(promotion -> promotion.getBranchId() == null || Objects.equals(promotion.getBranchId(), branchId))
                .toList();
    }

    /**
     * Picks the best item/category promotion for one cart line and stacks the cashier's
     * manual discount on top of it.
     *
     * <p>{@code cartBaseSubtotal} is the whole cart at list price, before any discount. It
     * exists so that {@code minBillAmount} can be honoured here as well as at bill level:
     * comparing against a post-discount figure would be circular, since the discount is
     * what this method is deciding. Both {@code minBillAmount} and {@code maxDiscountAmount}
     * were silently ignored on this path until now — the columns were stored and the
     * engine never read them, so a capped percentage promotion gave away an uncapped one.
     */
    public PromotionApplication calculateBestDiscount(
            Item item,
            Long branchId,
            double unitPrice,
            int normalizedQty,
            DiscountType manualType,
            double manualValue,
            double cartBaseSubtotal,
            List<Promotion> activePromotions
    ) {
        DiscountType normalizedManualType = manualType == null ? DiscountType.NONE : manualType;
        double baseLineTotal = lineTotal(item, unitPrice, normalizedQty);
        double safeCartSubtotal = Math.max(0, cartBaseSubtotal);

        Promotion bestPromotion = null;
        PromotionTarget bestTarget = null;
        double bestPromotionDiscount = 0.0;
        double bestUncappedDiscount = 0.0;

        for (Promotion promotion : activePromotions == null ? List.<Promotion>of() : activePromotions) {
            if (promotion.getScope() != PromotionScope.ITEM && promotion.getScope() != PromotionScope.CATEGORY) {
                continue;
            }
            if (safeCartSubtotal < Math.max(0, promotion.getMinBillAmount())) {
                continue;
            }
            PromotionTarget target = matchingTarget(promotion, item, branchId);
            if (target == null) {
                continue;
            }
            double promoFinalUnitPrice = resolveOfferUnitPrice(promotion, target, unitPrice);
            if (!marginAllows(promotion, item, promoFinalUnitPrice)) {
                continue;
            }
            double promoLineTotal = lineTotal(item, promoFinalUnitPrice, normalizedQty);
            double uncappedDiscount = roundMoney(baseLineTotal - promoLineTotal);
            double promoDiscount = capLineDiscount(promotion, uncappedDiscount);
            if (promoDiscount > bestPromotionDiscount) {
                bestPromotion = promotion;
                bestTarget = target;
                bestPromotionDiscount = promoDiscount;
                bestUncappedDiscount = uncappedDiscount;
            }
        }

        if (bestPromotion != null) {
            double promoFinalUnitPrice = discountedUnitPrice(unitPrice, baseLineTotal, bestPromotionDiscount);
            double stackedFinalUnitPrice = calculateFinalUnitPrice(promoFinalUnitPrice, normalizedManualType, manualValue);
            double promoLineTotal = lineTotal(item, promoFinalUnitPrice, normalizedQty);
            double finalLineTotal = lineTotal(item, stackedFinalUnitPrice, normalizedQty);
            double manualDiscountAmount = roundMoney(promoLineTotal - finalLineTotal);
            double appliedDiscountAmount = roundMoney(baseLineTotal - finalLineTotal);
            // The caller rebuilds the final unit price from this type/value pair, so it has to
            // reproduce the price actually charged. A rate survives only when nothing altered
            // it: a manual discount on top, maxDiscountAmount biting, or a per-item offer price
            // (which is a price, not a rate) all mean the line no longer sells at that rate, and
            // the pair collapses to the flat per-unit reduction. Returning PERCENT there would
            // re-expand to the uncapped discount downstream.
            boolean capApplied = bestPromotionDiscount < bestUncappedDiscount;
            boolean promotionRateIntact = normalizedManualType == DiscountType.NONE
                    && !capApplied
                    && bestTarget.getOfferPrice() == null;
            DiscountType effectiveDiscountType = promotionRateIntact
                    ? effectiveRateType(bestPromotion, bestTarget)
                    : DiscountType.FIXED;
            double effectiveDiscountValue = promotionRateIntact
                    ? effectiveRateValue(bestPromotion, bestTarget)
                    : roundMoney(Math.max(0, unitPrice - stackedFinalUnitPrice));

            return new PromotionApplication(
                    bestPromotion.getId(),
                    bestPromotion.getName(),
                    effectiveDiscountType,
                    effectiveDiscountValue,
                    manualDiscountAmount,
                    bestPromotionDiscount,
                    appliedDiscountAmount,
                    baseLineTotal,
                    finalLineTotal,
                    true
            );
        }

        double manualFinalUnitPrice = calculateFinalUnitPrice(unitPrice, normalizedManualType, manualValue);
        double manualLineTotal = lineTotal(item, manualFinalUnitPrice, normalizedQty);
        double manualDiscountAmount = roundMoney(baseLineTotal - manualLineTotal);
        return new PromotionApplication(
                null,
                null,
                normalizedManualType,
                normalizedManualType == DiscountType.NONE ? 0.0 : Math.max(0, manualValue),
                manualDiscountAmount,
                bestPromotionDiscount,
                manualDiscountAmount,
                baseLineTotal,
                manualLineTotal,
                false
        );
    }

    public PromotionOrderApplication calculateBestOrderDiscount(
            Long branchId,
            Long customerId,
            double baseTotal,
            double manualBillDiscount,
            List<Promotion> activePromotions
    ) {
        double safeBaseTotal = Math.max(0, baseTotal);
        double manualDiscountAmount = roundMoney(Math.max(0, Math.min(manualBillDiscount, safeBaseTotal)));
        Promotion bestPromotion = null;
        double bestPromotionDiscount = 0.0;

        for (Promotion promotion : activePromotions == null ? List.<Promotion>of() : activePromotions) {
            if (promotion.getScope() != PromotionScope.BILL && promotion.getScope() != PromotionScope.CUSTOMER) {
                continue;
            }
            if (!matchesOrderPromotion(promotion, branchId, customerId, safeBaseTotal)) {
                continue;
            }

            double promoDiscount = calculateOrderPromotionDiscount(promotion, safeBaseTotal);
            if (promoDiscount > bestPromotionDiscount) {
                bestPromotion = promotion;
                bestPromotionDiscount = promoDiscount;
            }
        }

        if (bestPromotion != null && bestPromotionDiscount > manualDiscountAmount) {
            return new PromotionOrderApplication(
                    bestPromotion.getId(),
                    bestPromotion.getName(),
                    bestPromotion.getDiscountType(),
                    bestPromotion.getDiscountValue(),
                    manualDiscountAmount,
                    bestPromotionDiscount,
                    bestPromotionDiscount,
                    safeBaseTotal,
                    roundMoney(safeBaseTotal - bestPromotionDiscount),
                    true
            );
        }

        return new PromotionOrderApplication(
                null,
                null,
                DiscountType.FIXED,
                manualDiscountAmount,
                manualDiscountAmount,
                bestPromotionDiscount,
                manualDiscountAmount,
                safeBaseTotal,
                roundMoney(safeBaseTotal - manualDiscountAmount),
                false
        );
    }

    public PromotionPreviewResponse preview(PromotionPreviewRequest request) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, request.getBranchId());
        LocalDateTime now = LocalDateTime.now();
        List<Promotion> activePromotions = activePromotionsForBranch(branchId, now);
        List<PromotionPreviewItemResponse> items = new ArrayList<>();

        // Resolve every line first: minBillAmount is judged against the whole cart at list
        // price, so the subtotal has to be known before the first line is priced.
        List<ResolvedLine> lines = new ArrayList<>();
        double cartBaseSubtotal = 0;
        for (OrderItemRequest itemRequest : request.getItems()) {
            Item item = itemRepository.findById(itemRequest.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemRequest.getItemId()));
            int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, itemRequest.getQty(), itemRequest.getQtyUnit());
            lines.add(new ResolvedLine(item, itemRequest, normalizedQty));
            cartBaseSubtotal += lineTotal(item, itemRequest.getUnitPrice(), normalizedQty);
        }

        for (ResolvedLine line : lines) {
            Item item = line.item();
            OrderItemRequest itemRequest = line.request();
            PromotionApplication application = calculateBestDiscount(
                    item,
                    branchId,
                    itemRequest.getUnitPrice(),
                    line.normalizedQty(),
                    itemRequest.getDiscountType(),
                    itemRequest.getDiscountValue(),
                    cartBaseSubtotal,
                    activePromotions
            );
            items.add(PromotionPreviewItemResponse.builder()
                    .itemId(item.getId())
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
                    .build());
        }

        double subtotalAfterLineDiscounts = items.stream()
                .mapToDouble(PromotionPreviewItemResponse::getFinalLineTotal)
                .sum();
        double promotionDiscountTotal = items.stream()
                .filter(PromotionPreviewItemResponse::isPromotionApplied)
                .mapToDouble(PromotionPreviewItemResponse::getPromotionDiscountAmount)
                .sum();
        PromotionOrderApplication orderApplication = calculateBestOrderDiscount(
                branchId,
                request.getCustomerId(),
                subtotalAfterLineDiscounts,
                request.getBillDiscount(),
                activePromotions
        );
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
                .build();
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
        if (request.getDiscountType() == null || request.getDiscountType() == DiscountType.NONE) {
            throw new BadRequestException("Promotion discount type must be PERCENT or FIXED");
        }
        if (request.getDiscountValue() <= 0) {
            throw new BadRequestException("Promotion discount value must be greater than 0");
        }
        if (request.getDiscountType() == DiscountType.PERCENT && request.getDiscountValue() > 100) {
            throw new BadRequestException("Percent discount cannot exceed 100");
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
                            .discountValue(promotion.getDiscountValue())
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
        if (promotion.getEndAt() != null && promotion.getEndAt().isBefore(now)) {
            return "ENDED";
        }
        if (!promotion.isActive()) {
            return "PAUSED";
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
                .priority(source.getPriority())
                .marginFloorPercent(source.getMarginFloorPercent())
                .allowBelowCost(source.isAllowBelowCost())
                .build();

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

        return mapResponse(promotionRepository.save(copy));
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

    /**
     * The target row this item matched, or null if the promotion does not cover it.
     *
     * <p>Returns the row rather than a boolean because the row is where a per-item offer price
     * lives — the caller needs to know not just whether the promotion applies but which of its
     * entries applied.
     *
     * <p>A category promotion matches on either the item's sub-category or its parent category.
     * An item with no sub-category has neither and matches nothing; the schema still permits
     * such a row even though the API requires one, so this degrades quietly rather than
     * dereferencing null.
     */
    private PromotionTarget matchingTarget(Promotion promotion, Item item, Long branchId) {
        if (promotion.getBranchId() != null && !Objects.equals(promotion.getBranchId(), branchId)) {
            return null;
        }
        if (promotion.getScope() == PromotionScope.ITEM) {
            return promotion.getTargets().stream()
                    .filter(target -> Objects.equals(target.getItemId(), item.getId()))
                    .findFirst()
                    .orElse(null);
        }

        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory != null ? subCategory.getCategory() : null;
        Long subCategoryId = subCategory != null ? subCategory.getId() : null;
        Long categoryId = category != null ? category.getId() : null;

        return promotion.getTargets().stream()
                .filter(target ->
                        (target.getSubCategoryId() != null && Objects.equals(target.getSubCategoryId(), subCategoryId))
                                || (target.getCategoryId() != null && Objects.equals(target.getCategoryId(), categoryId)))
                .findFirst()
                .orElse(null);
    }

    /**
     * The unit price this item sells at under this promotion.
     *
     * <p>Resolution order is offer price, then per-item rate, then the promotion's own rate.
     * Each step is more specific than the next, and the last is what every target row written
     * before per-item pricing existed falls through to.
     *
     * <p>An offer price above list is a price increase, which is never what was meant — save
     * time rejects it, and anything that still reaches here is clamped to list rather than
     * charging a customer more than the shelf label.
     */
    private double resolveOfferUnitPrice(Promotion promotion, PromotionTarget target, double unitPrice) {
        if (target != null && target.getOfferPrice() != null) {
            double offer = target.getOfferPrice().doubleValue();
            if (offer < 0) {
                return 0;
            }
            return Math.min(offer, unitPrice);
        }
        if (target != null && target.getDiscountType() != null
                && target.getDiscountType() != DiscountType.NONE && target.getDiscountValue() != null) {
            return calculateFinalUnitPrice(unitPrice, target.getDiscountType(), target.getDiscountValue().doubleValue());
        }
        return calculateFinalUnitPrice(unitPrice, promotion.getDiscountType(), promotion.getDiscountValue());
    }

    private DiscountType effectiveRateType(Promotion promotion, PromotionTarget target) {
        if (target != null && target.getDiscountType() != null && target.getDiscountType() != DiscountType.NONE) {
            return target.getDiscountType();
        }
        return promotion.getDiscountType();
    }

    private double effectiveRateValue(Promotion promotion, PromotionTarget target) {
        if (target != null && target.getDiscountType() != null
                && target.getDiscountType() != DiscountType.NONE && target.getDiscountValue() != null) {
            return target.getDiscountValue().doubleValue();
        }
        return promotion.getDiscountValue();
    }

    /**
     * Whether a promotion may price this line, given what the item costs.
     *
     * <p>Both checks are silent skips rather than errors: a promotion that would sell one item
     * below cost should stop applying to that item, not fail the sale. The operator is warned
     * at configuration time instead, which is where the price was typed.
     *
     * <p>An item with no cost price is left alone — there is nothing to measure against, and
     * refusing on missing data would disable promotions on incompletely set up catalogues.
     */
    private boolean marginAllows(Promotion promotion, Item item, double discountedUnitPrice) {
        BigDecimal costPrice = item.getCostPrice();
        if (costPrice == null) {
            return true;
        }
        double cost = costPrice.doubleValue();

        if (!promotion.isAllowBelowCost() && discountedUnitPrice < cost) {
            return false;
        }

        BigDecimal floor = promotion.getMarginFloorPercent();
        if (floor != null) {
            if (discountedUnitPrice <= 0) {
                return false;
            }
            double marginPercent = ((discountedUnitPrice - cost) / discountedUnitPrice) * 100.0;
            return marginPercent >= floor.doubleValue();
        }
        return true;
    }

    private boolean matchesOrderPromotion(Promotion promotion, Long branchId, Long customerId, double baseTotal) {
        if (promotion.getBranchId() != null && !Objects.equals(promotion.getBranchId(), branchId)) {
            return false;
        }
        if (baseTotal < Math.max(0, promotion.getMinBillAmount())) {
            return false;
        }
        if (promotion.getScope() == PromotionScope.BILL) {
            return true;
        }
        if (promotion.getScope() == PromotionScope.CUSTOMER) {
            if (customerId == null || customerId <= 0) {
                return false;
            }
            return promotion.getTargets().stream()
                    .anyMatch(target -> Objects.equals(target.getCustomerId(), customerId));
        }
        return false;
    }

    private double calculateOrderPromotionDiscount(Promotion promotion, double baseTotal) {
        double discount;
        if (promotion.getDiscountType() == DiscountType.PERCENT) {
            double safePercent = Math.max(0, Math.min(100, promotion.getDiscountValue()));
            discount = baseTotal * (safePercent / 100.0);
        } else if (promotion.getDiscountType() == DiscountType.FIXED) {
            discount = Math.max(0, promotion.getDiscountValue());
        } else {
            discount = 0;
        }
        double maxDiscount = Math.max(0, promotion.getMaxDiscountAmount());
        if (maxDiscount > 0) {
            discount = Math.min(discount, maxDiscount);
        }
        return roundMoney(Math.min(baseTotal, discount));
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
                .discountValue(promotion.getDiscountValue())
                .minBillAmount(promotion.getMinBillAmount())
                .maxDiscountAmount(promotion.getMaxDiscountAmount())
                .startAt(promotion.getStartAt())
                .endAt(promotion.getEndAt())
                .branchId(promotion.getBranchId())
                .active(promotion.isActive())
                .priority(promotion.getPriority())
                .marginFloorPercent(promotion.getMarginFloorPercent())
                .allowBelowCost(promotion.isAllowBelowCost())
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

    private double lineTotal(Item item, double unitPrice, int normalizedQty) {
        return roundMoney(QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(unitPrice), normalizedQty).doubleValue());
    }

    /**
     * Clamps one line's promotion discount to {@code maxDiscountAmount}. A zero cap means
     * "no cap" — the column is NOT NULL and defaults to 0, so it cannot distinguish
     * "uncapped" from "give nothing away", and uncapped is the only reading that keeps
     * every promotion written before this cap existed working.
     *
     * <p>The cap is per line, not per cart: it reads as a ceiling on what this promotion
     * takes off this item, which is also what makes it a usable guard against a mistyped
     * offer price.
     */
    private double capLineDiscount(Promotion promotion, double lineDiscount) {
        double cap = Math.max(0, promotion.getMaxDiscountAmount());
        if (cap <= 0) {
            return lineDiscount;
        }
        return roundMoney(Math.min(lineDiscount, cap));
    }

    /**
     * Converts a line-level discount back into the unit price that produces it.
     *
     * <p>Everything downstream — manual discount stacking, the persisted
     * {@code finalUnitPrice} — works in unit-price space, but a capped discount is only
     * expressible as an amount. Scaling works because {@link #lineTotal} is linear in the
     * unit price, so it holds for weight and measure items as well as whole units.
     */
    private double discountedUnitPrice(double unitPrice, double baseLineTotal, double lineDiscount) {
        if (baseLineTotal <= 0) {
            return unitPrice;
        }
        double ratio = (baseLineTotal - lineDiscount) / baseLineTotal;
        if (ratio < 0) {
            ratio = 0;
        }
        return unitPrice * ratio;
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
