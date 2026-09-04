package com.chala.posapp.service;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.PromotionTarget;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.PromotionRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * The pricing half of {@link PromotionService} — the code that decides how much money leaves
 * the business — had no direct test until now.
 *
 * <p>Both calculators are pure: they take the active promotions as an argument and touch no
 * repository, so these run without a Spring context.
 *
 * <p>Quantities are normalized base units. A NORMAL item is priced per 1000 of them, so a
 * quantity of 2 pieces is {@code 2000} and a Rs. 100 unit price gives a Rs. 200 line.
 */
class PromotionServiceTest {

    private static final int TWO_PIECES = 2000;
    private static final int ONE_PIECE = 1000;

    private PromotionService service;

    @BeforeEach
    void setUp() {
        service = new PromotionService(
                mock(PromotionRepository.class),
                mock(ItemRepository.class),
                mock(CategoryRepository.class),
                mock(SubCategoryRepository.class),
                mock(BranchRepository.class),
                mock(CustomerRepository.class),
                mock(SecurityUtils.class)
        );
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private Item item(long id, long subCategoryId, long categoryId) {
        Category category = new Category();
        category.setId(categoryId);
        category.setName("Category " + categoryId);

        SubCategory subCategory = new SubCategory();
        subCategory.setId(subCategoryId);
        subCategory.setName("SubCategory " + subCategoryId);
        subCategory.setCategory(category);

        return Item.builder()
                .id(id)
                .name("Item " + id)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(60))
                .sellingPrice(BigDecimal.valueOf(100))
                .subCategory(subCategory)
                .build();
    }

    /** An item with no sub-category, and therefore no category to match a CATEGORY promotion on. */
    private Item uncategorisedItem(long id) {
        return Item.builder()
                .id(id)
                .name("Item " + id)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(60))
                .sellingPrice(BigDecimal.valueOf(100))
                .build();
    }

    private Promotion itemPromotion(long id, String name, DiscountType type, double value, long targetItemId) {
        Promotion promotion = Promotion.builder()
                .id(id)
                .name(name)
                .scope(PromotionScope.ITEM)
                .discountType(type)
                .discountValue(value)
                .active(true)
                .build();
        promotion.getTargets().add(PromotionTarget.builder().promotion(promotion).itemId(targetItemId).build());
        return promotion;
    }

    private Promotion categoryPromotion(long id, DiscountType type, double value, Long categoryId, Long subCategoryId) {
        Promotion promotion = Promotion.builder()
                .id(id)
                .name("Category promo " + id)
                .scope(PromotionScope.CATEGORY)
                .discountType(type)
                .discountValue(value)
                .active(true)
                .build();
        promotion.getTargets().add(PromotionTarget.builder()
                .promotion(promotion)
                .categoryId(categoryId)
                .subCategoryId(subCategoryId)
                .build());
        return promotion;
    }

    private Promotion billPromotion(long id, DiscountType type, double value) {
        return Promotion.builder()
                .id(id)
                .name("Bill promo " + id)
                .scope(PromotionScope.BILL)
                .discountType(type)
                .discountValue(value)
                .active(true)
                .build();
    }

    private PromotionApplication priceLine(Item item, double unitPrice, int qty, double cartSubtotal, List<Promotion> promos) {
        return service.calculateBestDiscount(item, 1L, unitPrice, qty, DiscountType.NONE, 0, cartSubtotal, promos);
    }

    // ── H-1: caps were stored and never read on the line path ───────────────────────────

    @Nested
    @DisplayName("maxDiscountAmount on item and category promotions")
    class MaxDiscountCap {

        @Test
        @DisplayName("clamps a percentage that would otherwise give away far more than the cap")
        void capsPercentDiscount() {
            // 20% of a Rs. 200,000 line is Rs. 40,000 against a Rs. 500 intent. This is the
            // exact shape of the bug: the cap was accepted, persisted, and ignored.
            Promotion promo = itemPromotion(1, "Electronics 20%", DiscountType.PERCENT, 20, 7L);
            promo.setMaxDiscountAmount(500);

            PromotionApplication result = priceLine(item(7, 3, 2), 100_000, TWO_PIECES, 200_000, List.of(promo));

            assertThat(result.promotionApplied()).isTrue();
            assertThat(result.promotionDiscountAmount()).isEqualTo(500.0);
            assertThat(result.finalLineTotal()).isEqualTo(199_500.0);
        }

        @Test
        @DisplayName("leaves a discount smaller than the cap untouched")
        void doesNotCapBelowThreshold() {
            Promotion promo = itemPromotion(1, "10% off", DiscountType.PERCENT, 10, 7L);
            promo.setMaxDiscountAmount(500);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.promotionDiscountAmount()).isEqualTo(20.0);
            assertThat(result.finalLineTotal()).isEqualTo(180.0);
        }

        @Test
        @DisplayName("treats a zero cap as no cap, so promotions written before the cap existed still work")
        void zeroCapMeansUncapped() {
            Promotion promo = itemPromotion(1, "20% off", DiscountType.PERCENT, 20, 7L);
            promo.setMaxDiscountAmount(0);

            PromotionApplication result = priceLine(item(7, 3, 2), 1000, TWO_PIECES, 2000, List.of(promo));

            assertThat(result.promotionDiscountAmount()).isEqualTo(400.0);
        }

        @Test
        @DisplayName("returns a discount type the caller can replay without re-expanding to the uncapped value")
        void cappedResultSurvivesDownstreamRecompute() {
            // OrderService rebuilds finalUnitPrice from the returned type/value. If a capped
            // PERCENT came back as PERCENT, that rebuild would hand back the uncapped discount.
            Promotion promo = itemPromotion(1, "20% capped", DiscountType.PERCENT, 20, 7L);
            promo.setMaxDiscountAmount(500);

            Item item = item(7, 3, 2);
            PromotionApplication result = priceLine(item, 100_000, TWO_PIECES, 200_000, List.of(promo));

            assertThat(result.discountType()).isEqualTo(DiscountType.FIXED);

            double replayedUnitPrice = 100_000 - result.discountValue();
            double replayedLineTotal = replayedUnitPrice * (TWO_PIECES / 1000.0);
            assertThat(replayedLineTotal).isCloseTo(result.finalLineTotal(), within(0.01));
        }

        @Test
        @DisplayName("keeps the promotion's own percentage when nothing altered it")
        void uncappedPercentIsReportedAsPercent() {
            Promotion promo = itemPromotion(1, "20% off", DiscountType.PERCENT, 20, 7L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.discountType()).isEqualTo(DiscountType.PERCENT);
            assertThat(result.discountValue()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("caps a category promotion the same way")
        void capsCategoryPromotion() {
            Promotion promo = categoryPromotion(1, DiscountType.PERCENT, 50, 2L, null);
            promo.setMaxDiscountAmount(100);

            PromotionApplication result = priceLine(item(7, 3, 2), 1000, TWO_PIECES, 2000, List.of(promo));

            assertThat(result.promotionDiscountAmount()).isEqualTo(100.0);
        }
    }

    // ── H-1: minBillAmount on the line path ─────────────────────────────────────────────

    @Nested
    @DisplayName("minBillAmount on item and category promotions")
    class MinimumBill {

        @Test
        @DisplayName("skips the promotion when the cart at list price is below the minimum")
        void skipsBelowMinimum() {
            Promotion promo = itemPromotion(1, "Spend 5000", DiscountType.PERCENT, 10, 7L);
            promo.setMinBillAmount(5000);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.promotionApplied()).isFalse();
            assertThat(result.promotionDiscountAmount()).isZero();
            assertThat(result.finalLineTotal()).isEqualTo(200.0);
        }

        @Test
        @DisplayName("applies once the cart reaches the minimum")
        void appliesAtMinimum() {
            Promotion promo = itemPromotion(1, "Spend 5000", DiscountType.PERCENT, 10, 7L);
            promo.setMinBillAmount(5000);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 5000, List.of(promo));

            assertThat(result.promotionApplied()).isTrue();
            assertThat(result.promotionDiscountAmount()).isEqualTo(20.0);
        }
    }

    // ── selection ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("choosing between competing promotions")
    class Selection {

        @Test
        @DisplayName("the promotion giving the customer the most wins")
        void largestDiscountWins() {
            Promotion small = itemPromotion(1, "10% off", DiscountType.PERCENT, 10, 7L);
            Promotion large = itemPromotion(2, "30% off", DiscountType.PERCENT, 30, 7L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(small, large));

            assertThat(result.promotionId()).isEqualTo(2L);
            assertThat(result.promotionDiscountAmount()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("a cap can change which promotion wins")
        void capChangesTheWinner() {
            Promotion cappedBig = itemPromotion(1, "30% capped at 10", DiscountType.PERCENT, 30, 7L);
            cappedBig.setMaxDiscountAmount(10);
            Promotion uncappedSmall = itemPromotion(2, "10% off", DiscountType.PERCENT, 10, 7L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(cappedBig, uncappedSmall));

            assertThat(result.promotionId()).isEqualTo(2L);
            assertThat(result.promotionDiscountAmount()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("on an exact tie the promotion listed first — the higher priority — keeps it")
        void priorityBreaksTies() {
            // The repository returns promotions ordered by priority DESC, and the comparison is
            // a strict >, so priority decides ties and nothing else.
            Promotion higherPriority = itemPromotion(1, "First", DiscountType.PERCENT, 10, 7L);
            Promotion lowerPriority = itemPromotion(2, "Second", DiscountType.PERCENT, 10, 7L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200,
                    List.of(higherPriority, lowerPriority));

            assertThat(result.promotionId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a promotion pinned to another branch does not apply")
        void branchScoped() {
            Promotion otherBranch = itemPromotion(1, "Branch 2 only", DiscountType.PERCENT, 10, 7L);
            otherBranch.setBranchId(2L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(otherBranch));

            assertThat(result.promotionApplied()).isFalse();
        }
    }

    // ── category matching (H-2 in the audit turned out to be sound; this pins it) ────────

    @Nested
    @DisplayName("category matching")
    class CategoryMatching {

        @Test
        @DisplayName("matches an item through its sub-category's parent category")
        void matchesViaParentCategory() {
            Promotion promo = categoryPromotion(1, DiscountType.PERCENT, 10, 2L, null);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.promotionApplied()).isTrue();
        }

        @Test
        @DisplayName("matches an item directly on its sub-category")
        void matchesViaSubCategory() {
            Promotion promo = categoryPromotion(1, DiscountType.PERCENT, 10, null, 3L);

            PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.promotionApplied()).isTrue();
        }

        @Test
        @DisplayName("an item with no sub-category is skipped rather than blowing up")
        void uncategorisedItemDoesNotMatch() {
            // sub_category_id is nullable in the schema even though the API requires it, so a
            // legacy or hand-edited row must degrade quietly instead of throwing at checkout.
            Promotion promo = categoryPromotion(1, DiscountType.PERCENT, 10, 2L, 3L);

            PromotionApplication result = priceLine(uncategorisedItem(7), 100, TWO_PIECES, 200, List.of(promo));

            assertThat(result.promotionApplied()).isFalse();
        }
    }

    // ── manual discount interaction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("manual discount alongside a promotion")
    class ManualDiscount {

        @Test
        @DisplayName("stacks on top of the promotion at line level, compounding on the discounted price")
        void manualStacksOnPromotion() {
            Promotion promo = itemPromotion(1, "10% off", DiscountType.PERCENT, 10, 7L);

            PromotionApplication result = service.calculateBestDiscount(
                    item(7, 3, 2), 1L, 100, TWO_PIECES, DiscountType.PERCENT, 10, 200, List.of(promo));

            // 100 → 90 (promotion) → 81 (manual), so the line is 162 rather than 160.
            assertThat(result.finalLineTotal()).isEqualTo(162.0);
            assertThat(result.promotionDiscountAmount()).isEqualTo(20.0);
            assertThat(result.manualDiscountAmount()).isEqualTo(18.0);
            assertThat(result.appliedDiscountAmount()).isEqualTo(38.0);
        }

        @Test
        @DisplayName("applies alone when no promotion matches")
        void manualOnly() {
            PromotionApplication result = service.calculateBestDiscount(
                    item(7, 3, 2), 1L, 100, TWO_PIECES, DiscountType.FIXED, 25, 200, List.of());

            assertThat(result.promotionApplied()).isFalse();
            assertThat(result.finalLineTotal()).isEqualTo(150.0);
            assertThat(result.appliedDiscountAmount()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a fixed discount larger than the price floors the line at zero")
        void manualCannotGoNegative() {
            PromotionApplication result = service.calculateBestDiscount(
                    item(7, 3, 2), 1L, 100, ONE_PIECE, DiscountType.FIXED, 500, 100, List.of());

            assertThat(result.finalLineTotal()).isZero();
        }
    }

    // ── bill level ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bill level")
    class BillLevel {

        @Test
        @DisplayName("honours maxDiscountAmount")
        void capsBillPromotion() {
            Promotion promo = billPromotion(1, DiscountType.PERCENT, 20);
            promo.setMaxDiscountAmount(500);

            PromotionOrderApplication result = service.calculateBestOrderDiscount(
                    1L, null, 10_000, 0, List.of(promo));

            assertThat(result.promotionApplied()).isTrue();
            assertThat(result.promotionDiscountAmount()).isEqualTo(500.0);
            assertThat(result.finalTotal()).isEqualTo(9_500.0);
        }

        @Test
        @DisplayName("skips a promotion whose minimum bill is not met")
        void honoursMinimumBill() {
            Promotion promo = billPromotion(1, DiscountType.PERCENT, 20);
            promo.setMinBillAmount(5_000);

            PromotionOrderApplication result = service.calculateBestOrderDiscount(
                    1L, null, 1_000, 0, List.of(promo));

            assertThat(result.promotionApplied()).isFalse();
            assertThat(result.finalTotal()).isEqualTo(1_000.0);
        }

        @Test
        @DisplayName("takes the better of the manual discount and the promotion, never both")
        void betterOfManualOrPromotion() {
            // Deliberately different from the line path, which stacks. Pinning it so the
            // difference is a decision on record rather than an accident.
            Promotion promo = billPromotion(1, DiscountType.PERCENT, 10);

            PromotionOrderApplication promotionWins = service.calculateBestOrderDiscount(
                    1L, null, 10_000, 200, List.of(promo));
            assertThat(promotionWins.promotionApplied()).isTrue();
            assertThat(promotionWins.appliedDiscountAmount()).isEqualTo(1_000.0);

            PromotionOrderApplication manualWins = service.calculateBestOrderDiscount(
                    1L, null, 10_000, 2_000, List.of(promo));
            assertThat(manualWins.promotionApplied()).isFalse();
            assertThat(manualWins.appliedDiscountAmount()).isEqualTo(2_000.0);
        }

        @Test
        @DisplayName("never discounts more than the bill is worth")
        void discountCannotExceedTotal() {
            Promotion promo = billPromotion(1, DiscountType.FIXED, 50_000);

            PromotionOrderApplication result = service.calculateBestOrderDiscount(
                    1L, null, 1_000, 0, List.of(promo));

            assertThat(result.promotionDiscountAmount()).isEqualTo(1_000.0);
            assertThat(result.finalTotal()).isZero();
        }
    }

    // ── money ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rounds a discount that lands on a fraction of a cent to two decimals")
    void roundsToTwoDecimals() {
        Promotion promo = itemPromotion(1, "33% off", DiscountType.PERCENT, 33.333, 7L);

        PromotionApplication result = priceLine(item(7, 3, 2), 99.99, ONE_PIECE, 99.99, List.of(promo));

        assertThat(BigDecimal.valueOf(result.promotionDiscountAmount()).scale()).isLessThanOrEqualTo(2);
        assertThat(BigDecimal.valueOf(result.finalLineTotal()).scale()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("an empty promotion list leaves the line at list price")
    void noPromotionsLeavesListPrice() {
        PromotionApplication result = priceLine(item(7, 3, 2), 100, TWO_PIECES, 200, List.of());

        assertThat(result.promotionApplied()).isFalse();
        assertThat(result.finalLineTotal()).isEqualTo(200.0);
        assertThat(result.appliedDiscountAmount()).isZero();
    }
}
