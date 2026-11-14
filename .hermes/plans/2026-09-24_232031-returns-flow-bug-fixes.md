# Returns Flow — Verified Bug Fix Plan

> **For Hermes:** implement task-by-task; keep commits scoped to `backend/`.

**Goal:** Fix the four real defects verified in the sales-return flow (STORE_CREDIT no-op, promo
cap never released on a completed multi-part return, server/preview cent mismatch, batch restore
rounding slop), plus three stale unit comments left by the V52 fix.

**Architecture:** All changes live in `backend/` (Java 21 / Spring Boot). No schema change is
required except one optional data repair migration for promotions; no frontend change is required
except the optional cent alignment. Fixes are additive and backward compatible — no module keys,
no API paths, no entity columns are renamed.

**Tech stack:** Java 21, Spring Boot 4.0.1, JPA/Hibernate, Mockito + AssertJ (unit),
Spring Boot Test (integration), MySQL 8 (Flyway, programmatic per tenant).

**Verified test command (this host):**
```bash
cd "D:/My Projects/POS System/backend"
java -classpath 'C:/apache-maven-3.9.15/boot/plexus-classworlds-2.9.0.jar' \
  '-Dclassworlds.conf=C:/apache-maven-3.9.15/bin/m2.conf' \
  '-Dmaven.home=C:/apache-maven-3.9.15' \
  '-Dmaven.multiModuleProjectDirectory=D:/My Projects/POS System/backend' \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test -Dtest=ClassName
```
(`./mvnw` does not exist; plain `mvn` fails on this host due to MSYS path conversion.)

---

## Current context / assumptions

Already done and **not** part of this plan (working tree, uncommitted, branch
`chore/api-path-alignment`):

- `OrderReturnService.java` — display/base unit conversion at the request boundary
  (`normalizedReturnQty`), cap compared display-vs-display, stock restore and promotion share
  ratio both base-unit.
- `backend/src/main/resources/db/migration/tenant/V52__repair_return_stock_units.sql` — repairs
  batches already short-changed by the old unit bug; marks rows with `stock_restore_repaired`.

Assumptions:

1. Bug 1 is a **gap**, not intended: there is no store-credit/wallet concept anywhere in the
   backend (verified: 0 hits for `storeCredit|store_credit|creditBalance|wallet|creditNote`).
   The chosen fix is to **stop lying** (reject), not to invent a wallet — the latter is a product
   decision that needs its own feature.
2. Bug 2's guard is **deliberate for true partials** but wrong when the order ends up fully
   returned. Fix must not double-reverse ledger rows an earlier partial already reversed.
3. Bug 3's fix direction is chosen to match the **server** (per-line rounding), because the server
   is the system of record; the preview follows it. The reverse would require changing stored
   money math.

---

## Task 1 — Bug 1: reject STORE_CREDIT when the sale was not a credit sale

**Objective:** a return can no longer claim a store credit that the system has no way to grant.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java` (~line 90, the
  refund-method validation block)
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/CreateReturnRequest.java:17` (comment)
- Modify: `backend/src/main/java/com/chala/posapp/entity/OrderReturn.java:52` (comment)
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/OrderReturnResponse.java:34` (comment)
- Modify: `frontend/pos-frontend/src/pages/SaleReturnPage.jsx` (~line 626, warning → block submit)
- Test: `backend/src/test/java/com/chala/posapp/service/OrderReturnServiceTest.java` (new)

**Step 1: write the failing test**

```java
@Test
@DisplayName("STORE_CREDIT is refused on a sale that was not a credit sale")
void storeCreditRefusedOnCashSale() {
    // order type CASH, customer linked, refundMethod STORE_CREDIT
    CreateReturnRequest req = returnRequest("STORE_CREDIT", orderItemId(1L), 1);
    assertThatThrownBy(() -> service.processReturn("INV-2026-06-B1-000001", req))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Store credit can only be refunded on a credit sale");
}

@Test
@DisplayName("STORE_CREDIT is still allowed on a credit sale and reduces the due")
void storeCreditAllowedOnCreditSale() { /* assert customer.dueAmount reduced */ }
```

**Step 2: run it, expect failure** — currently the call *succeeds* on a cash sale, so the first
assertion fails with no exception.

**Step 3: implement** — in `OrderReturnService.processReturn`, right after the
`validMethods.contains` check:

```java
// STORE_CREDIT is a promise the system can only keep on a credit sale: this codebase has
// no customer wallet or credit-note ledger, so a cash sale refunded as store credit would
// write the method onto the return and the receipt and record the credit nowhere. Reject it
// rather than print a slip for money that does not exist. A real store-credit balance is a
// feature, not a line of code here.
if ("STORE_CREDIT".equals(refundMethod)
        && (order.getOrderType() != OrderType.CREDIT || order.getCustomerId() == null)) {
    throw new BadRequestException(
            "Store credit can only be refunded on a credit sale. "
                    + "Use cash, bank or card for this sale.");
}
```

Then delete the now-dead second half of the gate at the old line 305 so the block reads simply
`if ("STORE_CREDIT".equals(refundMethod)) { ...reduce due... }` (guaranteed CREDIT + customer).

**Step 4: run tests, expect pass.**

**Step 5: frontend** — mirror the rule so the operator learns it while choosing, not from a 400.
Same pattern as `password.js` mirroring `@PasswordComplexity`. In `SaleReturnPage.jsx`, where the
amber warning is rendered, disable the option/submit:

```jsx
const storeCreditBlocked = refundMethod === "STORE_CREDIT"
  && (sale.orderType !== "CREDIT" || !sale.customerId);
```
and add `storeCreditBlocked` to the submit `disabled` expression + the hint text.

**Step 6: commit** — `fix: reject store-credit refunds on non-credit sales`.

---

## Task 2 — Bug 2: release the redemption counter when a return completes the order

**Objective:** a customer who returned everything is no longer counted as having used the promo.

**Design (do not deviate):** the whole-order path must only reverse ledger rows that are still
live, and must **not** touch rows an earlier partial return already reversed or already gave part
of back. So the fix is a third path, not a wider `undoesWholeSale`:

- `undoesWholeSale` (existing) = first return, fully returned → `reverseForOrder` (unchanged).
- **new** `completesTheOrder` = `fullyReturned && existingCount > 0` → after running the existing
  proportional `reverseForReturn` (which correctly gives back the last slice of budget), additionally
  **release the counter** by decrementing `times_redeemed` once per promotion that was originally
  consumed on this order, and once per code, floored at zero.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/service/PromotionRedemptionService.java`
  (add `releaseCountersForCompletedReturn(Long orderId)`)
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java` (call it after
  `reverseForReturn` when `fullyReturned && existingCount > 0`)
- Test: `backend/src/test/java/com/chala/posapp/service/PromotionReturnReversalTest.java` (extend)

**Step 1: write the failing test**

```java
@Test
@DisplayName("a return that completes the order releases the redemption counter, once")
void completedReturnReleasesCounter() {
    // order had 1 live redemption row, 1 code consumed
    promotions.releaseCountersForCompletedReturn(ORDER_ID);
    verify(promotionRepository).release(eq(PROMO_ID), any());   // times_redeemed - 1
    verify(codeRepository).release(CODE_ID);
}

@Test
@DisplayName("...and is idempotent: a second call releases nothing")
void completedReturnReleasesOnce() { /* second call -> verify(..., never()) */ }
```

Idempotency source: only rows where `reversedAt IS NULL` are counted (a plain
`findByOrderIdAndReversedAtIsNull` returns nothing after the earlier partials reversed them), so
the second call is a no-op.

**Step 2: run, expect failure** — method does not exist.

**Step 3: implement** in `PromotionRedemptionService`:

```java
/**
 * Gives the redemption *count* back when returns have taken the whole order, the counterpart of
 * {@link #reverseForReturn}, which deliberately keeps it. A partial return genuinely used the
 * promotion on what the customer kept; an order that came back in full did not.
 *
 * <p>Counts live ledger rows only, so a second call after an earlier partial already reversed
 * them releases nothing: this is safe to call whenever a return completes an order.
 */
@Transactional
public void releaseCountersForCompletedReturn(Long orderId) {
    List<PromotionRedemption> live = redemptionRepository.findByOrderIdAndReversedAtIsNull(orderId);
    if (live.isEmpty()) {
        return;
    }
    Map<Long, BigDecimal> perPromotion = new LinkedHashMap<>();
    for (PromotionRedemption row : live) {
        perPromotion.merge(row.getPromotionId(), row.getDiscountAmount(), BigDecimal::add);
    }
    perPromotion.forEach(promotionRepository::release);
    live.stream().map(PromotionRedemption::getPromotionCodeId)
            .filter(Objects::nonNull).distinct().forEach(codeRepository::release);
    log.info("Released redemption counters for fully returned order {}", orderId);
}
```

In `OrderReturnService`, inside the `else` branch after `reverseForReturn(...)`:

```java
// The last partial that emptied the order: the money was given back above in proportion.
// The count has to come back too, or the customer is refused a promotion they never kept.
if (fullyReturned) {
    promotionRedemptionService.releaseCountersForCompletedReturn(order.getId());
}
```

**Step 4: run, expect pass.**

**Step 5: commit** — `fix: release promotion counters when returns complete an order`.

---

## Task 3 — Bug 3: align the cashier preview with the server's per-line rounding

**Objective:** the amount read out at the counter equals the amount the server pays.

**Files:**
- Modify: `frontend/pos-frontend/src/pages/SaleReturnPage.jsx:166-170`
- Test: manual (frontends have lint only, no runner — per `AGENTS.md`)

**Step 1: change the preview math to per-line rounding, then sum**

```jsx
const round2 = (n) => Math.round(n * 100) / 100;
// Round each line to the refund, then add: the server rounds per line too. Summing first and
// rounding once is a cent different whenever the share has a remainder, and the cashier reads
// the preview figure out loud.
const pointsValueBack = round2(selectedLines.reduce((s, l) => s + round2(l.refundLine * settlement.pointsShare), 0));
const totalRefund    = round2(selectedLines.reduce((s, l) => s + round2(l.refundLine * settlement.cashShare), 0));
const discountShare  = Math.max(0, round2(goodsValue - pointsValueBack - totalRefund));
```

**Step 2: verify by hand** — three lines of `33.33` at `cashShare 0.8` must now show **79.98**
(was 79.99), matching the server.

**Step 3: commit** — `fix: preview refund rounds per line, matching the server`.

---

## Task 4 — Bug 5: make the batch restore exact instead of over-restoring

**Objective:** a line returned in several parts restores exactly what it took, never more.

**Design (verified numerically — 150 cases, 0 failures):** replace the per-batch `Math.ceil` with a
**cumulative apportionment**. Keep a running restored total for the line; on each return compute
the *target* allocation of that new total across usage rows, and restore only the difference.

```
target_i = apportion(C, usages, originalQty)     // sum(target) == C exactly
delta_i  = clamp(target_i - alreadyRestored_i, 0, usages_i - alreadyRestored_i)
```

`C = min(C_previous + normalizedReturnQty, originalQty)`. Apportionment = floor each
`C*u_i/originalQty`, then hand the leftover units to the largest fractional parts (ties by
ascending `u_i`, i.e. deterministically), each capped by `usages_i`.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java`
  (replace the body of `reverseStockForReturnItem`; add `alreadyRestoredQtyForItem` helper)
- Modify: `backend/src/main/java/com/chala/posapp/repository/OrderItemStockUsageRepository.java`
  (add `sumRestoredQtyByOrderItemId`)
- Migration: `backend/src/main/resources/db/migration/tenant/V53__usage_restored_qty.sql`
  (add `restored_quantity` to `order_item_stock_usages`, default 0)
- Test: `backend/src/test/java/com/chala/posapp/service/OrderReturnStockRestoreTest.java` (new)

**Step 1: migration** — copy the V10/V27/V28/V31-V52 guarded-procedure pattern exactly:

```sql
CALL _v53_add_col('order_item_stock_usages', 'restored_quantity', 'INT NOT NULL DEFAULT 0');
```

(Add the standard `DROP/CREATE PROCEDURE _v53_add_col` wrapper around it.)

**Step 2: entity** — `OrderItemStockUsage`: `@Column(name="restored_quantity", nullable=false) @Builder.Default private int restoredQuantity = 0;`

**Step 3: repository**:

```java
@Query("SELECT COALESCE(SUM(u.restoredQuantity), 0) FROM OrderItemStockUsage u WHERE u.orderItemId = :orderItemId")
int sumRestoredQtyByOrderItemId(@Param("orderItemId") Long orderItemId);
```

**Step 4: failing test** — pin the exact case the old code got wrong:

```java
@Test
@DisplayName("a line split across batches restores exactly what it took, across partials")
void multiBatchPartialReturnsRestoreExactly() {
    // 3 units sold: usage rows 1000 + 2000 (base units)
    // return 1000, then 1000, then 1000
    assertThat(batch(1).getQuantity()).isEqualTo(ORIGINAL_1 + 1000); // was +1002 before
    assertThat(batch(2).getQuantity()).isEqualTo(ORIGINAL_2 + 2000); // was +2001 before
}
```

**Step 5: implement** the apportionment + delta logic, persisting `restoredQuantity` on each usage
row as it is given back.

**Step 6: run, expect pass.** Also re-run `LoyaltyReturnIntegrationTest` and
`PromotionReturnReversalTest`.

**Step 7: commit** — `fix: exact per-batch stock restore across partial returns`.

**Risk:** V53's column starts at 0, so for lines returned **before** V52 the new tracking has no
history — but V52 already topped those batches up to the correct total and marked them, and
`alreadyRestoredQtyForItem` starts from 0 for them. A line returned once *before* V52 and once
*after* could therefore over-restore once. Given the shop in question had only 2–3 returns total,
document this in the migration comment and treat it as accepted; the alternative (reconstructing
prior per-batch restore history) is not recoverable from the data.

---

## Task 5 — stale comments (unit-drift cleanup)

**Objective:** comments must not describe the pre-V52 unit contract.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/ReturnItemRequest.java:14`
  — `// How many units to return (in base units, same as OrderItem.qty)` →
  `// How many units to return, in DISPLAY units (what the till shows), not base units.`
- Modify: `backend/src/main/java/com/chala/posapp/entity/OrderReturnItem.java:38` — already
  corrected in the V52 change; confirm no second stale copy exists.
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java` — confirm the
  class-level stock-reversal comment block names `normalizedReturnQty` as base units (it does).

**Step 1:** apply the one-line comment change. **Step 2:** `grep -rn "same base units as OrderItem.qty"`
must return nothing. **Step 3:** commit — `docs: correct return-quantity unit comments`.

---

## Tests / validation

| Layer | Command | Expect |
|---|---|---|
| Unit (new) | `... Launcher -o test -Dtest=OrderReturnServiceTest` | pass |
| Unit (new) | `... -Dtest=OrderReturnStockRestoreTest` | pass |
| Unit (extended) | `... -Dtest=PromotionReturnReversalTest` | pass |
| Integration | `... -Dtest=LoyaltyReturnIntegrationTest` | pass (needs Docker for MySQL; skips gracefully without) |
| Full | `... Launcher -o test` | pass |
| Frontend | `cd frontend/pos-frontend && npm run lint` | clean |
| Migrations | run backend once against local MySQL, then `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;` on a tenant DB | V52/V53 present, `success=1` |

Manual verification for Task 1 (the one with money attached): on a **cash** sale, open the return
screen, choose Store Credit → the option must be blocked in the UI and a direct
`POST /orders/{inv}/returns` with `"refundMethod":"STORE_CREDIT"` must return 400.

---

## Risks, tradeoffs, open questions

1. **Task 1 changes behaviour for an existing shop.** If anyone has already issued a store-credit
   return on a cash sale, that return is already in the DB with the method recorded. Nothing
   repairs it; it shows as a return that refunded no money anywhere. Worth a one-off report query
   before shipping:
   `SELECT r.return_no, r.refund_method, r.total_refund_amount FROM order_returns r JOIN orders o ON o.id = r.original_order_id WHERE r.refund_method = 'STORE_CREDIT' AND o.order_type <> 'CREDIT';`

2. **Bug 2's counter release is a deliberate asymmetry** with `reverseForReturn`'s documented rule
   ("the promotion was still used on that order"). The justification is narrow and worth stating in
   the commit: the rule is about goods the customer *kept*; when they kept none, it does not apply.

3. **Task 4's V53 does not know pre-V52 restore history** (see Task 4 Risk). Only one mixed
   before/after line per shop could be affected, and only once.

4. **Open question for the product owner:** should a store credit be a real feature (a customer
   balance / credit-note ledger)? If yes, that is a new module with a `ModuleCatalog` entry, a
   migration on `customers`, panel switches and a POS screen — a plan of its own, not a patch here.

5. **Not in scope (verified false):** "rounding drift accumulates across partials" and "frontend
   preview diverges on subTotal" — no code path can mutate order totals, and each return recomputes
   from the original ticket line so nothing compounds.