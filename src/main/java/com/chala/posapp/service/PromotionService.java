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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        return promotionRepository.findAllByOrderByActiveDescStartAtDescIdDesc().stream()
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
                .build();
        applyTargets(promotion, request);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse update(Long id, PromotionRequest request) {
        validateRequest(request);
        Promotion promotion = promotionRepository.findById(id)
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
        promotion.getTargets().clear();
        applyTargets(promotion, request);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionResponse updateStatus(Long id, boolean active) {
        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        promotion.setActive(active);
        return mapResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public void delete(Long id) {
        if (!promotionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Promotion not found");
        }
        promotionRepository.deleteById(id);
    }

    public List<Promotion> activePromotionsForBranch(Long branchId, LocalDateTime now) {
        return promotionRepository
                .findByActiveTrueAndStartAtLessThanEqualAndEndAtGreaterThanEqualOrderByPriorityDescIdDesc(now, now)
                .stream()
                .filter(promotion -> promotion.getBranchId() == null || Objects.equals(promotion.getBranchId(), branchId))
                .toList();
    }

    public PromotionApplication calculateBestDiscount(
            Item item,
            Long branchId,
            double unitPrice,
            int normalizedQty,
            DiscountType manualType,
            double manualValue,
            List<Promotion> activePromotions
    ) {
        DiscountType normalizedManualType = manualType == null ? DiscountType.NONE : manualType;
        double baseLineTotal = lineTotal(item, unitPrice, normalizedQty);

        Promotion bestPromotion = null;
        double bestPromotionDiscount = 0.0;

        for (Promotion promotion : activePromotions == null ? List.<Promotion>of() : activePromotions) {
            if (promotion.getScope() != PromotionScope.ITEM && promotion.getScope() != PromotionScope.CATEGORY) {
                continue;
            }
            if (!matches(promotion, item, branchId)) {
                continue;
            }
            double promoFinalUnitPrice = calculateFinalUnitPrice(unitPrice, promotion.getDiscountType(), promotion.getDiscountValue());
            double promoLineTotal = lineTotal(item, promoFinalUnitPrice, normalizedQty);
            double promoDiscount = roundMoney(baseLineTotal - promoLineTotal);
            if (promoDiscount > bestPromotionDiscount) {
                bestPromotion = promotion;
                bestPromotionDiscount = promoDiscount;
            }
        }

        if (bestPromotion != null) {
            double promoFinalUnitPrice = calculateFinalUnitPrice(unitPrice, bestPromotion.getDiscountType(), bestPromotion.getDiscountValue());
            double stackedFinalUnitPrice = calculateFinalUnitPrice(promoFinalUnitPrice, normalizedManualType, manualValue);
            double promoLineTotal = lineTotal(item, promoFinalUnitPrice, normalizedQty);
            double finalLineTotal = lineTotal(item, stackedFinalUnitPrice, normalizedQty);
            double manualDiscountAmount = roundMoney(promoLineTotal - finalLineTotal);
            double appliedDiscountAmount = roundMoney(baseLineTotal - finalLineTotal);
            DiscountType effectiveDiscountType = normalizedManualType == DiscountType.NONE
                    ? bestPromotion.getDiscountType()
                    : DiscountType.FIXED;
            double effectiveDiscountValue = normalizedManualType == DiscountType.NONE
                    ? bestPromotion.getDiscountValue()
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

        for (OrderItemRequest itemRequest : request.getItems()) {
            Item item = itemRepository.findById(itemRequest.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemRequest.getItemId()));
            int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, itemRequest.getQty(), itemRequest.getQtyUnit());
            PromotionApplication application = calculateBestDiscount(
                    item,
                    branchId,
                    itemRequest.getUnitPrice(),
                    normalizedQty,
                    itemRequest.getDiscountType(),
                    itemRequest.getDiscountValue(),
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
            List<Long> itemIds = distinctIds(request.getItemIds());
            if (itemIds.isEmpty()) {
                throw new BadRequestException("At least one item is required for item promotion");
            }
            if (itemRepository.findAllById(itemIds).size() != itemIds.size()) {
                throw new ResourceNotFoundException("One or more promotion items not found");
            }
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

    private void applyTargets(Promotion promotion, PromotionRequest request) {
        if (promotion.getScope() == PromotionScope.ITEM) {
            distinctIds(request.getItemIds()).forEach(itemId ->
                    promotion.getTargets().add(PromotionTarget.builder()
                            .promotion(promotion)
                            .itemId(itemId)
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

    private boolean matches(Promotion promotion, Item item, Long branchId) {
        if (promotion.getBranchId() != null && !Objects.equals(promotion.getBranchId(), branchId)) {
            return false;
        }
        if (promotion.getScope() == PromotionScope.ITEM) {
            return promotion.getTargets().stream()
                    .anyMatch(target -> Objects.equals(target.getItemId(), item.getId()));
        }

        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory != null ? subCategory.getCategory() : null;
        Long subCategoryId = subCategory != null ? subCategory.getId() : null;
        Long categoryId = category != null ? category.getId() : null;

        return promotion.getTargets().stream()
                .anyMatch(target ->
                        (target.getSubCategoryId() != null && Objects.equals(target.getSubCategoryId(), subCategoryId))
                                || (target.getCategoryId() != null && Objects.equals(target.getCategoryId(), categoryId)));
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
                .itemIds(promotion.getTargets().stream().map(PromotionTarget::getItemId).filter(Objects::nonNull).toList())
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
