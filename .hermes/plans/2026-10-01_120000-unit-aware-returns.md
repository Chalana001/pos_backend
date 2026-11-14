# Unit-Aware Sale Returns Implementation Plan

> **For Hermes:** Implement sequentially — one subagent, one task, verify before the next. No parallel children on this working tree.

**Goal:** Make sale returns unit-aware for WEIGHT/VOLUME items (return 700 G of a 2 KG sale correctly), and restore recipe ingredients on returns instead of skipping them.

**Architecture:** The backend already keeps display quantities (what the till shows) separate from normalized base units (what stock math uses). This fix threads fractional display quantities through the return chain and, at the boundary, normalizes them against the **return request's** unit — not the sale line's unit. Recipe restore reuses the existing cumulative usage apportionment, which is already dimensionally correct for RECIPE items because recipe quantities pass through unscaled (pieces).

**Tech Stack:** Java 21 / Spring Boot 4 / Flyway (tenant DB), React 19 / Vite / Tailwind.

---

## Verified root causes

| # | Bug | Evidence |
|---|-----|----------|
| 1 | Return page forces integer pcs mode | `frontend/pos-frontend/src/pages/SaleReturnPage.jsx:118-121` — `parseInt(value, 10) \|\| 0`; input at `:575-587` has no unit selector, while the sale cart has one at `Cart.jsx:471-484` |
| 2 | Backend rejects fractional return qty | `ReturnItemRequest.java:16-17` — `Integer returnQty` + `@Min(1)`; `OrderReturnItem.return_qty` is `int`; `V6__order_returns.sql:36` — `return_qty INT NOT NULL` |
| 3 | No unit field on return request | `OrderReturnService.java:157-161` normalizes using `originalItem.getQtyUnit()` (the **sale's** unit); money at `:163` is `returnQty × finalUnitPrice` where `finalUnitPrice` is per primary unit — entering 700 with no unit literally prices 700 KG |
| 4 | Recipe returns restore nothing | `OrderReturnService.java:468-472` early-returns `false` for `RECIPE`, but the sale consumed ingredients (`OrderService.java:979-1018`) and the usage-row restore at `OrderReturnService.java:477-508` would handle it correctly if reachable |

Out of scope (verified false / already fixed): the display-vs-base unit mismatch in `OrderReturnService` is already fixed and committed as `7c9362b`.

---

## Unit conventions (critical context for every task)

- `OrderItem.displayQty` / API `qty` / `order_return_items.return_qty` — **DISPLAY units** (pcs / kg / L, what the till shows)
- `OrderItem.qty` / `OrderItemStockUsage.quantity` / `StockBatch.quantity` — **NORMALIZED base units** (pcs×1000 / grams / millilitres)
- `finalUnitPrice` is **per primary display unit** (per KG, per L) on lines sold in a primary unit; lines sold in G/ML carry a per-small-unit price instead
- For RECIPE items, quantities pass through **unscaled** (pieces), so usage apportionment is dimensionally correct as-is

---

## Task 1: Fractional return quantities through the backend chain

**Objective:** `returnQty = 0.7` on a 2 KG sale passes validation, persists, and restores 700 g of stock.

**Files:**
- Create: `backend/src/main/resources/db/migration/tenant/V54__fractional_return_qty.sql`
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/ReturnItemRequest.java`
- Modify: `backend/src/main/java/com/chala/posapp/entity/OrderReturnItem.java`
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/OrderReturnItemResponse.java`
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java`
- Modify: `backend/src/test/java/com/chala/posapp/service/OrderReturnServiceTest.java`

**Step 1: Write failing test**

Add a test that builds a 2 KG WEIGHT sale line (`displayQty = 2`, `qty = 2000`, `qtyUnit = "KG"`, `finalUnitPrice` per KG) and submits `returnQty = 0.7`. Assert:
- No validation error
- `OrderReturnItem.returnQty` equals `0.7`
- Stock batch gains exactly `700` base units
- Refund line = `0.7 × finalUnitPrice`

**Step 2: Run test to verify failure**

```bash
cd "D:/My Projects/POS System/backend"
java -classpath 'C:/apache-maven-3.9.15/boot/plexus-classworlds-2.9.0.jar' \
  '-Dclassworlds.conf=C:/apache-maven-3.9.15/bin/m2.conf' '-Dmaven.home=C:/apache-maven-3.9.15' \
  '-Dmaven.multiModuleProjectDirectory=D:/My Projects/POS System/backend' \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test -Dtest="OrderReturnServiceTest"
```

Expected: FAIL — `returnQty` is `Integer`, `0.7` truncates or validation rejects.

**Step 3: Implement**

- V54 migration: `MODIFY return_qty INT → DECIMAL(12,3)` using the information_schema-guarded stored procedure pattern (copy V52's, do not invent a new one). MySQL 8 has no plain `ALTER ... IF EXISTS`.
- `ReturnItemRequest.returnQty` → `BigDecimal` with `@DecimalMin(value = "0.001", inclusive = true)` and `@Digits(integer = 9, fraction = 3)`
- `OrderReturnItem.returnQty` → `BigDecimal` (`@Column(name="return_qty", precision=12, scale=3)`)
- `OrderReturnItemResponse.returnQty` → `BigDecimal`
- `OrderReturnService`:
  - `ValidatedReturnLine.returnQty` → `BigDecimal`
  - Every comparison (`compareTo`, `signum`, `stripTrailingZeros`) already works on BigDecimal — keep it
  - `ticketLine = returnQty × finalUnitPrice` via `BigDecimal.multiply(...).setScale(2, HALF_UP)`
  - `sumReturnedQtyByOrderItemId` must sum DECIMAL, not INT — check the repository signature and adjust if it returns a numeric type that needs BigDecimal conversion
- Keep `normalizeSaleQuantity(...)` — it already returns `int` base units; verify `0.7` KG → `700` (it should multiply by 1000 via BigDecimal before truncating to int)

**Step 4: Run test to verify pass**

Same command as Step 2. Expected: PASS, and the existing 19 tests stay green.

**Step 5: Commit**

```bash
git add src/main/resources/db/migration/tenant/V54__fractional_return_qty.sql \
  src/main/java/com/chala/posapp/dto/returns/ReturnItemRequest.java \
  src/main/java/com/chala/posapp/entity/OrderReturnItem.java \
  src/main/java/com/chala/posapp/dto/returns/OrderReturnItemResponse.java \
  src/main/java/com/chala/posapp/service/OrderReturnService.java \
  src/test/java/com/chala/posapp/service/OrderReturnServiceTest.java
git commit -m "fix(returns): accept fractional display quantities"
```

---

## Task 2: Unit field on the return request + server-side conversion

**Objective:** The cashier returns "700 G"; the request carries `returnQty = 0.7` with `returnUnit = "G"`, and the server normalizes and prices it against the correct unit.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/dto/returns/ReturnItemRequest.java`
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java`
- Modify: `backend/src/test/java/com/chala/posapp/service/OrderReturnServiceTest.java`

**Step 1: Write failing test**

Two tests:
1. A 2 KG sale (`qtyUnit = "KG"`, `finalUnitPrice` per KG) returned as `0.7` with `returnUnit = "G"` — assert stock +700 g and refund = `0.7 × finalUnitPrice`.
2. A sale line stored in G (`qtyUnit = "G"`, `perSmallUnitPrice`) returned as `700` with `returnUnit = "G"` — assert stock +700 g and refund = `700 × perSmallUnitPrice`.

**Step 2: Run to verify failure**

Same Maven command. Expected: FAIL — no `returnUnit` field exists.

**Step 3: Implement**

- `ReturnItemRequest` gains `returnUnit` (`String`, optional). When absent, fall back to the original sale line's unit (backwards compatible with the POS's current payload).
- `OrderReturnService` validation block:
  - Resolve the unit: `returnUnit != null ? returnUnit.trim().toUpperCase() : originalItem.getQtyUnit()`
  - Validate it's one of `PCS`, `KG`, `G`, `L`, `ML` (reject anything else with a 400-style `BadRequestException`)
  - Convert the request's display quantity into the sale line's display quantity for persistence: if the return is in G/ML and the sale line is in KG/L, divide by 1000; if the return is in KG/L and the sale line is in G/ML, multiply by 1000; same-unit → as-is; PCS is only valid against PCS
  - Keep the cap check in the sale line's display units after this conversion
  - Pass the converted display quantity to `normalizeSaleQuantity(...)` so base units are derived from the sale line's unit, not the return's unit — this keeps one conversion path and avoids G-vs-KG double scaling
  - For money: if the sale line's `qtyUnit` is `G`/`ML`, price with `originalItem.getFinalUnitPrice()` (which is per small unit on those lines); otherwise price with `finalUnitPrice` per primary unit. The invoice template already encodes this convention (`InvoiceTemplate.jsx:38-50`) — mirror it, do not invent a new one
- Store `returnQty` in the sale line's display unit, and the chosen return unit in a new `return_unit` column (V55 migration, guarded ALTER) so the receipt can print "700 G" rather than "0.7 KG"
- Receipt: `OrderReturnItemResponse` gains `returnUnit`; `SaleReturnPage.jsx` and `ReturnReceiptPrinter.jsx` print it next to the qty (frontend wiring lands in Task 4, but the API contract lands here)

**Step 4: Run to verify pass**

Same Maven command. Expected: both new tests pass, 19 existing green.

**Step 5: Commit**

```bash
git add src/main/java/com/chala/posapp/dto/returns/ReturnItemRequest.java \
  src/main/java/com/chala/posapp/service/OrderReturnService.java \
  src/main/resources/db/migration/tenant/V55__return_unit.sql \
  src/main/java/com/chala/posapp/entity/OrderReturnItem.java \
  src/main/java/com/chala/posapp/dto/returns/OrderReturnItemResponse.java \
  src/test/java/com/chala/posapp/service/OrderReturnServiceTest.java
git commit -m "fix(returns): carry the cashier's chosen unit through the return"
```

---

## Task 3: Restore recipe ingredients on return

**Objective:** Returning 1 of 2 plates of a recipe item puts half of each ingredient's consumed quantity back.

**Files:**
- Modify: `backend/src/main/java/com/chala/posapp/service/OrderReturnService.java`
- Modify: `backend/src/test/java/com/chala/posapp/service/OrderReturnStockRestoreTest.java`

**Step 1: Write failing test**

Build a RECIPE order line: qty 2 (pieces), one ingredient usage row of 400 g flour, `restoredQuantity = 0`. Return qty 1. Assert:
- `stockReversed` is `true` (currently `false` — the skip)
- Flour batch gains `200` base units
- Usage row `restoredQuantity` = `200`
- A second return of the remaining 1 → +200 more, total exactly `400` (no double restore)

**Step 2: Run to verify failure**

Same Maven command, `-Dtest="OrderReturnStockRestoreTest"`. Expected: FAIL — `stockReversed` is `false`.

**Step 3: Implement**

- Remove `RECIPE` from the early-skip in `reverseStockForReturnItem` (keep `SERVICE`)
- The usage-row path (`OrderReturnService.java:477-508`) already:
  - Uses `originalItem.getQty()` (normalized, which for RECIPE = pieces, same scale as `normalizedReturnQty`)
  - Apportions cumulatively across batches with largest-remainder
  - Tracks `restoredQuantity` so partials never double-restore
- Verify only that the RECIPE branch reaches this path — no algorithm change should be needed. If a guard blocks it, fix that guard, not the apportionment
- Leave the single-batch fallback (`:513-520`) as a secondary path for legacy RECIPE rows without usage rows

**Step 4: Run to verify pass**

Same Maven command. Expected: PASS with the new assertions, existing stock-restore tests green.

**Step 5: Commit**

```bash
git add src/main/java/com/chala/posapp/service/OrderReturnService.java \
  src/test/java/com/chala/posapp/service/OrderReturnStockRestoreTest.java
git commit -m "fix(returns): restore recipe ingredients proportionally"
```

---

## Task 4: Unit-aware return entry UI

**Objective:** The return page shows a KG/G or L/ML dropdown for measured items, accepts fractional quantities, converts to the sale line's unit before sending, and displays the chosen unit in the preview and success table.

**Files:**
- Modify: `frontend/pos-frontend/src/pages/SaleReturnPage.jsx`
- Modify: `frontend/pos-frontend/src/components/pos/ReturnReceiptPrinter.jsx` (only if it needs `returnUnit` wired into the printed line)
- Test: `frontend/pos-frontend/src/utils/stockQuantity.js` helpers are already exported — add a small unit test file if a frontend test runner exists; there is none, so ESLint + manual browser verification is the gate

**Step 1: Failing check (manual, documented)**

Open the return page for a WEIGHT sale, try to type `0.7` — it truncates to `0`. This is the documented RED state.

**Step 2: Implement**

- `returnQtys` state → store `{ qty, unit }` per item (default unit = the sale line's `qtyUnit`)
- `setQtyForItem` → `parseFloat` with 3-decimal precision, clamp to `maxReturnableQty`, no `parseInt`
- Per-line `CustomSelect` with `getMeasuredUnitOptions(item)` logic — reuse the cart's exact logic (`Cart.jsx:89-94`): `defaultUnit` L/ML → `[L, ML]`, otherwise `[KG, G]`. Drive it from `itemType`/`defaultUnit`, so a new item type following the same conventions gets the right selector for free
- On unit change, convert the typed number (700 G ↔ 0.7 KG) so the input never lies
- Cap, Already-Returned and Max columns get unit suffixes via `formatQuantityWithUnit`
- Preview refund line: mirror the server's exact formula — if sale line's `qtyUnit` is G/ML, `returnQty × perSmallUnitPrice`; else `returnQty × finalUnitPrice` (the server resolves this in Task 2; the frontend must match)
- Submit payload: send `returnUnit` alongside `returnQty`, both in the cashier's chosen unit
- Success table and receipt print the chosen unit next to the returned qty

**Step 3: Verify**

```bash
cd "D:/My Projects/POS System/frontend/pos-frontend"
npx eslint src/pages/SaleReturnPage.jsx src/components/pos/ReturnReceiptPrinter.jsx --max-warnings=0
```

Expected: 0 errors. Then manual browser check: sale 2 KG, return 700 G → slip, stock and refund all agree.

**Step 4: Commit**

```bash
git add src/pages/SaleReturnPage.jsx src/components/pos/ReturnReceiptPrinter.jsx
git commit -m "fix(returns): unit-aware quantity entry on the return page"
```

---

## Task 5: Full regression

Run everything before reporting done:

```bash
# Backend
cd "D:/My Projects/POS System/backend"
java -classpath 'C:/apache-maven-3.9.15/boot/plexus-classworlds-2.9.0.jar' \
  '-Dclassworlds.conf=C:/apache-maven-3.9.15/bin/m2.conf' '-Dmaven.home=C:/apache-maven-3.9.15' \
  '-Dmaven.multiModuleProjectDirectory=D:/My Projects/POS System/backend' \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test -Dtest="OrderReturnServiceTest,OrderReturnStockRestoreTest,PromotionReturnReversalTest"

# Frontend
cd "D:/My Projects/POS System/frontend/pos-frontend"
npx eslint src/pages/SaleReturnPage.jsx src/components/pos/ReturnReceiptPrinter.jsx --max-warnings=0
```

Expected: `BUILD SUCCESS`, `Tests run: N, Failures: 0, Errors: 0`, ESLint 0 errors.

No commit in this task unless a regression fix was required.

---

## Risks and tradeoffs

- **`returnQty` type change ripples wider than the return flow.** Any consumer reading `returnQty` as `int` (reports, the super-admin panel) must be checked. The task greps for `.getReturnQty()` / `.returnQty(` and verifies each before the commit goes in.
- **The G/ML price convention must be confirmed against live data.** The code encodes it (`InvoiceTemplate.jsx:38-50`) but a test proving `finalUnitPrice` is per small unit on a G-sold line pins it before the money math ships.
- **Old clients send no `returnUnit`.** The fallback to the sale line's unit keeps them working — this is deliberate backwards compatibility, not a silent assumption.
- **V54/V55 have not run against a real MySQL.** H2 in the test profile can mask MySQL-only DDL failures; the guarded procedure pattern mitigates but live verification is still the final gate.
- **`return_unit` on old rows is null.** Receipts and UI must render the sale line's unit when `returnUnit` is absent, not a blank cell.

## Out of scope

- Adding a customer wallet / store-credit ledger (the refund method is correctly rejected on non-credit sales)
- Any change to the sale-side cart (it already handles units correctly)
- The earlier display/base unit mismatch — already fixed and committed as `7c9362b`
