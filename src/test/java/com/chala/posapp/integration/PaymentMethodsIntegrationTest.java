package com.chala.posapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The complete lifecycle of a database-backed payment method: the seed gives
 * every tenant the methods the code previously hardcoded, an admin's new
 * method reaches the till with no deploy, and the flags — not the display
 * name — decide the financial behaviour (cash-like drawer treatment, required
 * references, credit). Deactivation and in-flight config changes must be
 * enforced on the backend. (Per-tenant isolation of the rows themselves is
 * verified against real per-tenant MySQL catalogs; the H2 suite shares one
 * catalog so it cannot represent it.)
 */
class PaymentMethodsIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String tenantId, String token, String adminToken) {
    }

    private Shop shop(String key) throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey(key), 1);
        return new Shop(
                fixture,
                fixture.tenantId(),
                login(fixture.tenantId(), fixture.cashier().getUsername(), DEFAULT_PASSWORD),
                login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD));
    }

    private JsonNode createMethod(Shop shop, String json) throws Exception {
        return postJson("/payment-methods", shop.tenantId(), shop.adminToken(), json);
    }

    @Test
    @DisplayName("the seed gives a fresh tenant the methods the code previously hardcoded")
    void freshTenantIsSeededWithDefaultMethods() throws Exception {
        Shop shop = shop("seed");

        JsonNode active = getJson("/payment-methods/active", shop.tenantId(), shop.token());
        assertThat(active.isArray()).isTrue();
        // Exactly the four default tenders: no STORE_CREDIT / CHEQUE / LEGACY —
        // those are refund/purchase/backfill codes, not checkout methods an
        // admin manages, and anything beyond these four is the admin's own.
        assertThat(active.size()).isEqualTo(4);

        // The four sale tenders the till offered before this feature existed, with
        // the financial behaviour the old hardcoded rules encoded: only cash feeds
        // the drawer, only credit defers, bank requires a reference.
        java.util.List<String> codes = new java.util.ArrayList<>();
        active.forEach(m -> codes.add(m.path("code").asText()));
        assertThat(codes).contains("CASH", "CARD", "BANK", "CREDIT");

        JsonNode cash = methodByCode(active, "CASH");
        assertThat(cash.path("includeInExpectedCash").asBoolean()).isTrue();
        assertThat(cash.path("allowsCredit").asBoolean()).isFalse();

        JsonNode credit = methodByCode(active, "CREDIT");
        assertThat(credit.path("allowsCredit").asBoolean()).isTrue();
        assertThat(credit.path("includeInExpectedCash").asBoolean()).isFalse();

        JsonNode bank = methodByCode(active, "BANK");
        assertThat(bank.path("referencePolicy").asText()).isEqualTo("REQUIRED");
        assertThat(bank.path("referenceLabel").asText()).isEqualTo("Bank Reference");
    }

    @Test
    @DisplayName("a newly created method is immediately usable at checkout with no deploy")
    void newMethodIsUsableAtCheckout() throws Exception {
        Shop shop = shop("new-method");

        JsonNode created = createMethod(shop, """
                {"displayName": "QR Payment", "category": "MOBILE_DIGITAL",
                 "referencePolicy": "OPTIONAL", "referenceLabel": "QR Transaction Reference",
                 "includeInExpectedCash": false, "allowsCredit": false,
                 "iconKey": "QrCode", "active": true}
                """);
        assertThat(created.path("code").asText()).isEqualTo("QR_PAYMENT");
        assertThat(created.path("displayName").asText()).isEqualTo("QR Payment");
        assertThat(created.path("system").asBoolean()).isFalse();

        // The cashier sees it in the active list the till renders.
        JsonNode active = getJson("/payment-methods/active", shop.tenantId(), shop.token());
        assertThat(methodByCode(active, "QR_PAYMENT")).isNotNull();

        // Codes are unique; a duplicate is refused, not silently merged.
        jsonRequest(HttpMethod.POST, "/payment-methods", shop.tenantId(), shop.adminToken(), """
                {"displayName": "Another QR", "category": "OTHER",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true, "code": "QR_PAYMENT"}
                """, 409);
    }

    @Test
    @DisplayName("a required reference is enforced on the backend with the configured label")
    void requiredReferenceIsBackendEnforced() throws Exception {
        Shop shop = shop("ref-policy");

        createMethod(shop, """
                {"displayName": "Voucher", "category": "OTHER",
                 "referencePolicy": "REQUIRED", "referenceLabel": "Voucher Code",
                 "includeInExpectedCash": false, "allowsCredit": false, "active": true}
                """);

        long branchId = shop.fixture.mainBranch().getId();
        long itemId = seedSellableItem(shop, "Voucher Item");

        // Missing reference: rejected before any sale is recorded.
        JsonNode missing = jsonRequest(HttpMethod.POST, "/orders", shop.tenantId(), shop.token(), """
                {"branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY", "billDiscount": 0,
                 "paidAmount": 10, "paymentMethod": "VOUCHER",
                 "items": [{"itemId": %d, "qty": 1, "unitPrice": 10, "discountType": "NONE", "discountValue": 0}]}
                """.formatted(branchId, itemId), 400);
        assertThat(missing.path("message").asText())
                .contains("Voucher Code is required for Voucher payments");

        // No items were consumed by the rejected attempt (nothing was sold).
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("deactivating a method blocks new sales but the method list keeps it for history")
    void deactivationBlocksNewSalesOnly() throws Exception {
        Shop shop = shop("deactivate");

        JsonNode created = createMethod(shop, """
                {"displayName": "Old Wallet", "category": "MOBILE_DIGITAL",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """);
        long id = created.path("id").asLong();

        JsonNode patch = patchJson("/payment-methods/%d/active".formatted(id), shop.tenantId(),
                shop.adminToken(), "{\"active\": false}");
        assertThat(patch.path("active").asBoolean()).isFalse();

        // Gone from the till's active list...
        JsonNode active = getJson("/payment-methods/active", shop.tenantId(), shop.token());
        assertThat(methodByCode(active, "OLD_WALLET")).isNull();

        // ...but still in the management list so historical sales can render it.
        JsonNode all = getJson("/payment-methods", shop.tenantId(), shop.adminToken());
        assertThat(methodByCode(all, "OLD_WALLET")).isNotNull();
    }

    @Test
    @DisplayName("an inactive method cannot be sold even when a client still sends its code")
    void inactiveMethodIsRejectedAtCheckout() throws Exception {
        Shop shop = shop("inactive-sale");

        JsonNode created = createMethod(shop, """
                {"displayName": "Paused Pay", "category": "OTHER",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """);
        long id = created.path("id").asLong();
        patchJson("/payment-methods/%d/active".formatted(id), shop.tenantId(),
                shop.adminToken(), "{\"active\": false}");

        long branchId = shop.fixture.mainBranch().getId();
        long itemId = seedSellableItem(shop, "Paused Item");
        JsonNode rejected = jsonRequest(HttpMethod.POST, "/orders", shop.tenantId(), shop.token(), """
                {"branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY", "billDiscount": 0,
                 "paidAmount": 10, "paymentMethod": "PAUSED_PAY",
                 "items": [{"itemId": %d, "qty": 1, "unitPrice": 10, "discountType": "NONE", "discountValue": 0}]}
                """.formatted(branchId, itemId), 400);
        assertThat(rejected.path("message").asText()).contains("inactive");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("the code is immutable: renaming the display name never rewrites history's key")
    void renameKeepsTheStableCode() throws Exception {
        Shop shop = shop("rename");

        JsonNode created = createMethod(shop, """
                {"displayName": "Wallet Pay", "category": "MOBILE_DIGITAL",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """);
        long id = created.path("id").asLong();

        JsonNode renamed = putJson("/payment-methods/%d".formatted(id), shop.tenantId(),
                shop.adminToken(), """
                {"displayName": "Wallet Pay Renamed", "category": "MOBILE_DIGITAL",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """);
        assertThat(renamed.path("code").asText()).isEqualTo("WALLET_PAY");
        assertThat(renamed.path("displayName").asText()).isEqualTo("Wallet Pay Renamed");
    }

    @Test
    @DisplayName("a cash-like configured method behaves like cash at the drawer")
    void cashLikeMethodFeedsExpectedCash() throws Exception {
        Shop shop = shop("cash-like");
        long branchId = shop.fixture.mainBranch().getId();

        createMethod(shop, """
                {"displayName": "Counter Voucher", "category": "CASH",
                 "referencePolicy": "NONE", "includeInExpectedCash": true,
                 "allowsCredit": false, "active": true}
                """);

        postJson("/shifts/open", shop.tenantId(), shop.token(), """
                {"openingCash": 1000, "note": "cash-like test"}
                """);

        // A sale paid by the cash-like method must land in the drawer bucket
        // (cashSales), exactly as a literal CASH tender would.
        long itemId = seedSellableItem(shop, "Voucher Item");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), """
                {"branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY", "billDiscount": 0,
                 "paidAmount": 50, "paymentMethod": "COUNTER_VOUCHER", "tenderedAmount": 50,
                 "items": [{"itemId": %d, "qty": 1, "unitPrice": 50, "discountType": "NONE", "discountValue": 0}]}
                """.formatted(branchId, itemId));
        assertThat(sale.path("paymentMethod").asText()).isEqualTo("COUNTER_VOUCHER");
        assertThat(sale.path("changeAmount").asDouble()).isCloseTo(0, within(0.001));

        JsonNode live = getJson("/shifts/me", shop.tenantId(), shop.token());
        assertThat(live.path("cashSales").asDouble()).isCloseTo(50, within(0.001));
    }

    @Test
    @DisplayName("a cashier reads the till's active methods but cannot manage them")
    void cashierCannotManageMethods() throws Exception {
        Shop shop = shop("roles");

        // The cashier's read is the endpoint the till itself uses.
        JsonNode active = getJson("/payment-methods/active", shop.tenantId(), shop.token());
        assertThat(active.isArray()).isTrue();

        // Management (list-all, create) is refused for the cashier role.
        jsonRequest(HttpMethod.GET, "/payment-methods", shop.tenantId(), shop.token(), null, 403);
        jsonRequest(HttpMethod.POST, "/payment-methods", shop.tenantId(), shop.token(), """
                {"displayName": "Should Not Exist", "category": "OTHER",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """, 403);
    }

    @Test
    @DisplayName("re-reading the management list does not duplicate or reset the seed")
    void seedingIsIdempotentAndPreservesEdits() throws Exception {
        Shop shop = shop("idempotent");

        // An admin deactivates a seeded method...
        JsonNode all = getJson("/payment-methods", shop.tenantId(), shop.adminToken());
        JsonNode card = methodByCode(all, "CARD");
        patchJson("/payment-methods/%d/active".formatted(card.path("id").asLong()),
                shop.tenantId(), shop.adminToken(), "{\"active\": false}");

        // ...and any read path that runs ensureDefaults again must not resurrect
        // or duplicate it.
        JsonNode again = getJson("/payment-methods", shop.tenantId(), shop.adminToken());
        assertThat(codesMatching(again, "CARD")).isEqualTo(1);
        assertThat(methodByCode(again, "CARD").path("active").asBoolean()).isFalse();
    }

    private JsonNode methodByCode(JsonNode list, String code) {
        for (JsonNode method : list) {
            if (code.equals(method.path("code").asText())) {
                return method;
            }
        }
        return null;
    }

    private long codesMatching(JsonNode list, String code) {
        long count = 0;
        for (JsonNode method : list) {
            if (code.equals(method.path("code").asText())) {
                count++;
            }
        }
        return count;
    }

    private long seedSellableItem(Shop shop, String name) throws Exception {
        com.chala.posapp.entity.Category category = new com.chala.posapp.entity.Category();
        category.setName(name + " Category");
        category = categoryRepository.save(category);
        com.chala.posapp.entity.SubCategory subCategory = new com.chala.posapp.entity.SubCategory();
        subCategory.setName(name + " Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode item = postJson("/items", shop.tenantId(), shop.adminToken(), """
                {"barcode": "%s", "name": "%s", "subCategoryId": %d,
                 "costPrice": 40, "sellingPrice": 50, "reorderLevel": 5}
                """.formatted(uniqueKey("BC"), name, subCategory.getId()));
        long itemId = item.path("id").asLong();

        JsonNode supplierNode = postJson("/suppliers", shop.tenantId(), shop.adminToken(), """
                {"name": "%s Supplier", "phone": "0777777777", "email": "%s@supplier.local",
                 "address": "Supplier lane", "active": true}
                """.formatted(name, uniqueKey("supplier")));
        postJson("/purchases", shop.tenantId(), shop.adminToken(), """
                {"supplierId": %d, "invoiceNo": "%s",
                 "branches": [{"branchId": %d, "items": [
                    {"itemId": %d, "qty": 6, "costPrice": 40, "sellingPrice": 50}]}]}
                """.formatted(supplierNode.path("id").asLong(), uniqueKey("PINV"),
                shop.fixture.mainBranch().getId(), itemId));
        return itemId;
    }
}
