package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixture corpus: the contract between this engine and the till's port of it.
 *
 * <p>Every scenario here is run through {@link PromotionEvaluator} and the result recorded. The
 * committed file is the golden master: this test fails if the engine's behaviour changes
 * without the corpus being regenerated, and the frontend's corpus test fails if the JS port
 * disagrees with it. Regenerate with
 * {@code mvn test -Dtest=PromotionCorpusTest -Dpromotion.corpus.write=true}, then copy the
 * file to {@code frontend/pos-frontend/src/offline/promotion-corpus.json}
 * ({@code scripts/sync-promotion-corpus.mjs} does that).
 *
 * <p>The JSON shape of the inputs is exactly what {@code GET /promotions/bundle} sends, so
 * this doubles as the wire-format contract for the bundle.
 */
class PromotionCorpusTest {

    static final int CORPUS_VERSION = 1;
    static final Path CORPUS = Path.of("src/test/resources/promotion-corpus/cases.json");

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 23, 59);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private static final class Promo {
        long id = 1; String name = "Promo"; PromotionScope scope = PromotionScope.ITEM;
        DiscountType type = DiscountType.PERCENT; BigDecimal value = BigDecimal.TEN;
        BigDecimal minBill = BigDecimal.ZERO; BigDecimal cap = BigDecimal.ZERO; Long branchId;
        PromotionEffectType effect = PromotionEffectType.DISCOUNT; BigDecimal buy; BigDecimal get;
        StackingMode stacking = StackingMode.BEST_ONLY; boolean manualOk = true; boolean belowCostOk = true;
        BigDecimal floor; List<TargetSnapshot> targets = new ArrayList<>(); List<TierSnapshot> tiers = new ArrayList<>();
        List<ScheduleSnapshot> schedules = new ArrayList<>();

        Promo id(long v) { id = v; return this; }
        Promo name(String v) { name = v; return this; }
        Promo scope(PromotionScope v) { scope = v; return this; }
        Promo rate(DiscountType t, double v) { type = t; value = BigDecimal.valueOf(v); return this; }
        Promo value(double v) { value = BigDecimal.valueOf(v); return this; }
        Promo minBill(double v) { minBill = BigDecimal.valueOf(v); return this; }
        Promo cap(double v) { cap = BigDecimal.valueOf(v); return this; }
        Promo branch(Long v) { branchId = v; return this; }
        Promo effect(PromotionEffectType v) { effect = v; return this; }
        Promo buy(double v) { buy = BigDecimal.valueOf(v); return this; }
        Promo get(double v) { get = BigDecimal.valueOf(v); return this; }
        Promo stacking(StackingMode v) { stacking = v; return this; }
        Promo noManual() { manualOk = false; return this; }
        Promo guardCost() { belowCostOk = false; return this; }
        Promo floor(double v) { floor = BigDecimal.valueOf(v); return this; }
        Promo item(long itemId) { targets.add(new TargetSnapshot(itemId, null, null, null, null, null, null)); return this; }
        Promo itemAt(long itemId, double offer) { targets.add(new TargetSnapshot(itemId, null, null, null, BigDecimal.valueOf(offer), null, null)); return this; }
        Promo itemRate(long itemId, DiscountType t, double v) { targets.add(new TargetSnapshot(itemId, null, null, null, null, t, BigDecimal.valueOf(v))); return this; }
        Promo category(long c) { targets.add(new TargetSnapshot(null, c, null, null, null, null, null)); return this; }
        Promo customer(long c) { targets.add(new TargetSnapshot(null, null, null, c, null, null, null)); return this; }
        Promo qtyTier(double q, DiscountType t, double v) { tiers.add(new TierSnapshot(BigDecimal.valueOf(q), null, t, BigDecimal.valueOf(v))); return this; }
        Promo amountTier(double a, DiscountType t, double v) { tiers.add(new TierSnapshot(null, BigDecimal.valueOf(a), t, BigDecimal.valueOf(v))); return this; }
        Promo schedule(int days, LocalTime from, LocalTime to) { schedules.add(new ScheduleSnapshot(days, from, to)); return this; }

        PromotionSnapshot build() {
            return new PromotionSnapshot(id, name, scope, type, value, minBill, cap, START, END, branchId, 0,
                    floor, belowCostOk, effect, buy, get, stacking, manualOk,
                    List.copyOf(targets), List.copyOf(tiers), List.copyOf(schedules));
        }
    }

    private static PricingLine line(long itemId, long categoryId, double unitPrice, double cost, int pieces) {
        return new PricingLine(itemId, ItemType.NORMAL, 3L, categoryId, BigDecimal.valueOf(unitPrice),
                BigDecimal.valueOf(cost), pieces * 1000, DiscountType.NONE, BigDecimal.ZERO);
    }

    private static PricingLine weightLine(long itemId, double unitPrice, double cost, int grams) {
        return new PricingLine(itemId, ItemType.WEIGHT, 3L, 2L, BigDecimal.valueOf(unitPrice),
                BigDecimal.valueOf(cost), grams, DiscountType.NONE, BigDecimal.ZERO);
    }

    private static PricingLine manual(PricingLine l, DiscountType t, double v) {
        return new PricingLine(l.itemId(), l.itemType(), l.subCategoryId(), l.categoryId(), l.unitPrice(), l.costPrice(),
                l.normalizedQty(), t, BigDecimal.valueOf(v));
    }

    // ── scenarios ───────────────────────────────────────────────────────────────────────

    private record LineCase(String id, PricingLine line, Long branchId, double cartBase, List<PromotionSnapshot> promotions) {}
    private record OrderCase(String id, Long branchId, Long customerId, double baseTotal, double manual,
                             List<PricingLine> lines, boolean exclusive, List<PromotionSnapshot> promotions) {}

    private List<LineCase> lineCases() {
        PricingLine std = line(7, 2, 100, 60, 2);
        return List.of(
                new LineCase("line/plain-percent", std, 1L, 200, List.of(new Promo().item(7).build())),
                new LineCase("line/plain-fixed-amount", std, 1L, 200, List.of(new Promo().rate(DiscountType.FIXED, 15).item(7).build())),
                new LineCase("line/no-match", std, 1L, 200, List.of(new Promo().item(8).build())),
                new LineCase("line/category-via-parent", std, 1L, 200, List.of(new Promo().scope(PromotionScope.CATEGORY).category(2).build())),
                new LineCase("line/cap-bites", line(7, 2, 100_000, 60, 2), 1L, 200_000,
                        List.of(new Promo().rate(DiscountType.PERCENT, 20).cap(500).item(7).build())),
                new LineCase("line/cap-zero-is-uncapped", line(7, 2, 1000, 60, 2), 1L, 2000,
                        List.of(new Promo().rate(DiscountType.PERCENT, 20).cap(0).item(7).build())),
                new LineCase("line/min-bill-not-met", std, 1L, 200, List.of(new Promo().minBill(5000).item(7).build())),
                new LineCase("line/min-bill-met", std, 1L, 5000, List.of(new Promo().minBill(5000).item(7).build())),
                new LineCase("line/best-of-two", std, 1L, 200,
                        List.of(new Promo().id(1).name("10%").item(7).build(), new Promo().id(2).name("30%").rate(DiscountType.PERCENT, 30).item(7).build())),
                new LineCase("line/tie-goes-to-first", std, 1L, 200,
                        List.of(new Promo().id(1).name("First").item(7).build(), new Promo().id(2).name("Second").item(7).build())),
                new LineCase("line/branch-mismatch", std, 1L, 200, List.of(new Promo().branch(2L).item(7).build())),
                new LineCase("line/wrong-scope-bill", std, 1L, 200, List.of(new Promo().scope(PromotionScope.BILL).build())),
                new LineCase("line/offer-price", line(7, 2, 450, 200, 2), 1L, 900, List.of(new Promo().rate(DiscountType.PERCENT, 50).itemAt(7, 399).build())),
                new LineCase("line/offer-price-above-list", line(7, 2, 450, 200, 1), 1L, 450, List.of(new Promo().itemAt(7, 900).build())),
                new LineCase("line/per-item-rate", std, 1L, 200, List.of(new Promo().itemRate(7, DiscountType.PERCENT, 25).build())),
                new LineCase("line/fixed-price", line(7, 2, 150, 60, 2), 1L, 300,
                        List.of(new Promo().scope(PromotionScope.CATEGORY).effect(PromotionEffectType.FIXED_PRICE).value(100).category(2).build())),
                new LineCase("line/bogo-five", line(7, 2, 100, 1, 5), 1L, 500,
                        List.of(new Promo().effect(PromotionEffectType.BUY_X_GET_Y_FREE).buy(2).get(1).item(7).build())),
                new LineCase("line/bogo-two-no-group", line(7, 2, 100, 1, 2), 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.BUY_X_GET_Y_FREE).buy(2).get(1).item(7).build())),
                new LineCase("line/tier-reached", line(7, 2, 100, 1, 7), 1L, 700,
                        List.of(new Promo().effect(PromotionEffectType.TIERED).qtyTier(3, DiscountType.PERCENT, 10).qtyTier(6, DiscountType.PERCENT, 20).item(7).build())),
                new LineCase("line/tier-not-reached", line(7, 2, 100, 1, 2), 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.TIERED).qtyTier(3, DiscountType.PERCENT, 10).item(7).build())),
                new LineCase("line/below-cost-blocked", std, 1L, 200, List.of(new Promo().rate(DiscountType.PERCENT, 50).guardCost().item(7).build())),
                new LineCase("line/margin-floor-blocked", std, 1L, 200, List.of(new Promo().rate(DiscountType.PERCENT, 20).floor(30).item(7).build())),
                new LineCase("line/margin-floor-ok", std, 1L, 200, List.of(new Promo().rate(DiscountType.PERCENT, 10).floor(30).item(7).build())),
                new LineCase("line/manual-stacks", manual(std, DiscountType.PERCENT, 10), 1L, 200, List.of(new Promo().item(7).build())),
                new LineCase("line/manual-only", manual(std, DiscountType.FIXED, 25), 1L, 200, List.of()),
                new LineCase("line/manual-blocked", manual(std, DiscountType.PERCENT, 10), 1L, 200, List.of(new Promo().noManual().item(7).build())),
                new LineCase("line/stackable-adds", std, 1L, 200,
                        List.of(new Promo().id(1).name("20%").rate(DiscountType.PERCENT, 20).item(7).build(),
                                new Promo().id(2).name("5 each").rate(DiscountType.FIXED, 5).stacking(StackingMode.STACKABLE).item(7).build())),
                new LineCase("line/exclusive-wins", std, 1L, 200,
                        List.of(new Promo().id(1).name("Members").stacking(StackingMode.EXCLUSIVE).item(7).build(),
                                new Promo().id(2).name("5%").rate(DiscountType.PERCENT, 5).item(7).build(),
                                new Promo().id(3).name("5 off").rate(DiscountType.FIXED, 5).stacking(StackingMode.STACKABLE).item(7).build())),
                new LineCase("line/exclusive-loses", std, 1L, 200,
                        List.of(new Promo().id(1).name("Members").rate(DiscountType.PERCENT, 5).stacking(StackingMode.EXCLUSIVE).item(7).build(),
                                new Promo().id(2).name("20%").rate(DiscountType.PERCENT, 20).item(7).build(),
                                new Promo().id(3).name("5 off").rate(DiscountType.FIXED, 5).stacking(StackingMode.STACKABLE).item(7).build())),
                new LineCase("line/weight-item", weightLine(7, 1200, 800, 1500), 1L, 1800, List.of(new Promo().item(7).build())),
                new LineCase("line/awkward-rounding", line(7, 2, 99.99, 1, 1), 1L, 99.99, List.of(new Promo().rate(DiscountType.PERCENT, 33.333).item(7).build())),
                new LineCase("line/cart-level-effect-is-wrong-scope", std, 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.BUNDLE).buy(2).value(150).item(7).build())),
                // Share of profit. std is 100 a piece against a cost of 60, so the margin is 40
                // and a tenth of it is 4 - the price lands at 96, twice for two pieces.
                new LineCase("line/profit-share", std, 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.PROFIT_SHARE).value(10).item(7).build())),
                // The whole margin given away sells at cost exactly, and no further.
                new LineCase("line/profit-share-all-of-it", std, 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.PROFIT_SHARE).value(100).item(7).build())),
                // A line already at or under cost has no profit to share, so nothing comes off.
                new LineCase("line/profit-share-no-margin", line(7, 2, 100, 100, 2), 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.PROFIT_SHARE).value(50).item(7).build())),
                // Cost unknown: a share of an unknown profit would be a guess.
                new LineCase("line/profit-share-no-cost", line(7, 2, 100, 0, 2), 1L, 200,
                        List.of(new Promo().effect(PromotionEffectType.PROFIT_SHARE).value(10).item(7).build())),
                // Awkward arithmetic, to pin that both engines round in the same order.
                new LineCase("line/profit-share-rounding", line(7, 2, 99.99, 33.33, 1), 1L, 99.99,
                        List.of(new Promo().effect(PromotionEffectType.PROFIT_SHARE).value(33.333).item(7).build()))
        );
    }

    private List<OrderCase> orderCases() {
        List<PricingLine> cart = List.of(line(7, 2, 450, 1, 2), line(8, 2, 280, 1, 2), line(9, 5, 1000, 1, 1));
        double cartTotal = 450 * 2 + 280 * 2 + 1000;
        return List.of(
                new OrderCase("order/percent", 1L, null, 10_000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).build())),
                new OrderCase("order/cap", 1L, null, 10_000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).rate(DiscountType.PERCENT, 20).cap(500).build())),
                new OrderCase("order/min-bill", 1L, null, 1000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).minBill(5000).build())),
                new OrderCase("order/manual-wins", 1L, null, 10_000, 2000, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).build())),
                new OrderCase("order/promotion-wins", 1L, null, 10_000, 200, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).build())),
                new OrderCase("order/fixed-exceeds-total", 1L, null, 1000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.BILL).rate(DiscountType.FIXED, 50_000).build())),
                new OrderCase("order/customer-match", 1L, 42L, 1000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.CUSTOMER).customer(42).build())),
                new OrderCase("order/customer-mismatch", 1L, 43L, 1000, 0, List.of(), false, List.of(new Promo().scope(PromotionScope.CUSTOMER).customer(42).build())),
                new OrderCase("order/spend-ladder", 1L, null, 12_000, 0, List.of(), false,
                        List.of(new Promo().scope(PromotionScope.BILL).effect(PromotionEffectType.TIERED).amountTier(5000, DiscountType.FIXED, 500).amountTier(10_000, DiscountType.FIXED, 1200).build())),
                new OrderCase("order/bundle-any-three", 1L, null, cartTotal, 0, cart, false,
                        List.of(new Promo().scope(PromotionScope.CATEGORY).effect(PromotionEffectType.BUNDLE).buy(3).value(1000).category(2).build())),
                new OrderCase("order/cheapest-free", 1L, null, cartTotal, 0, cart, false,
                        List.of(new Promo().scope(PromotionScope.CATEGORY).effect(PromotionEffectType.CHEAPEST_FREE).buy(3).category(2).build())),
                new OrderCase("order/bundle-item-scope", 1L, null, cartTotal, 0, cart, false,
                        List.of(new Promo().scope(PromotionScope.ITEM).effect(PromotionEffectType.BUNDLE).buy(2).value(800).item(7).build())),
                new OrderCase("order/blocked-by-exclusive-line", 1L, null, 180, 0, List.of(), true, List.of(new Promo().scope(PromotionScope.BILL).build())),
                new OrderCase("order/stackable", 1L, null, 1000, 0, List.of(), false,
                        List.of(new Promo().id(1).scope(PromotionScope.BILL).build(),
                                new Promo().id(2).scope(PromotionScope.BILL).rate(DiscountType.FIXED, 100).stacking(StackingMode.STACKABLE).build()))
        );
    }

    // ── the test ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the committed corpus matches what this engine produces")
    void corpusMatchesEngine() throws IOException {
        // Compared as text, not as JsonNode. A BigDecimal serialises to a DecimalNode but parses
        // back as a DoubleNode, so tree equality fails on every decimal even when the numbers
        // are identical, and it would fail silently in the direction that matters, by never
        // agreeing. Both sides go through the same writer here, so the comparison is exact.
        String generated = mapper.writeValueAsString(generate()) + "\n";
        boolean write = Boolean.getBoolean("promotion.corpus.write");
        if (write || !Files.exists(CORPUS)) {
            Files.createDirectories(CORPUS.getParent());
            Files.writeString(CORPUS, generated, StandardCharsets.UTF_8);
            if (write) {
                return;
            }
        }
        String committed = Files.readString(CORPUS, StandardCharsets.UTF_8);
        assertThat(committed.replace("\r\n", "\n"))
                .withFailMessage("The pricing engine's behaviour has changed. If that is intended, regenerate the corpus with "
                        + "-Dpromotion.corpus.write=true, copy it to the frontend with "
                        + "scripts/sync-promotion-corpus.mjs, and make the JS port pass it.")
                .isEqualTo(generated.replace("\r\n", "\n"));
    }

    private ObjectNode generate() {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", CORPUS_VERSION);
        root.put("generatedBy", "PromotionCorpusTest");
        ArrayNode cases = root.putArray("cases");

        for (LineCase c : lineCases()) {
            LineEvaluation result = PromotionEvaluator.evaluateLine(c.line(), c.branchId(), BigDecimal.valueOf(c.cartBase()), c.promotions());
            ObjectNode node = cases.addObject();
            node.put("id", c.id());
            node.put("kind", "line");
            ObjectNode input = node.putObject("input");
            input.set("line", mapper.valueToTree(c.line()));
            input.put("branchId", c.branchId());
            input.put("cartBaseSubtotal", c.cartBase());
            input.set("promotions", mapper.valueToTree(c.promotions()));
            ObjectNode expected = node.putObject("expected");
            expected.set("application", mapper.valueToTree(result.application()));
            expected.set("decisions", mapper.valueToTree(result.decisions()));
        }
        for (OrderCase c : orderCases()) {
            OrderEvaluation result = PromotionEvaluator.evaluateOrder(c.branchId(), c.customerId(), BigDecimal.valueOf(c.baseTotal()),
                    BigDecimal.valueOf(c.manual()), c.lines(), c.exclusive(), c.promotions());
            ObjectNode node = cases.addObject();
            node.put("id", c.id());
            node.put("kind", "order");
            ObjectNode input = node.putObject("input");
            input.put("branchId", c.branchId());
            if (c.customerId() == null) input.putNull("customerId"); else input.put("customerId", c.customerId());
            input.put("baseTotal", c.baseTotal());
            input.put("manualBillDiscount", c.manual());
            input.set("pricedLines", mapper.valueToTree(c.lines()));
            input.put("linesHaveExclusive", c.exclusive());
            input.set("promotions", mapper.valueToTree(c.promotions()));
            ObjectNode expected = node.putObject("expected");
            expected.set("application", mapper.valueToTree(result.application()));
            expected.set("decisions", mapper.valueToTree(result.decisions()));
        }
        return root;
    }
}
