package com.chala.posapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

/** H2 coverage for the bounded purchase-search response and branch rules. */
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.chala.posapp.integration.SqlCaptureStatementInspector"
})
class PurchaseSearchEndpointIntegrationTest extends ApiIntegrationTestSupport {

    private final LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);

    @Test
    @DisplayName("purchase search matches the legacy purchase fields and avoids per-item stock queries")
    void purchaseSearchPreservesFieldsAndUsesBulkStockRead() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("qa");
        SqlCaptureStatementInspector.clear();
        JsonNode legacy = fixture.purchaseBaseline(data, data.mainBranchId());
        long legacyStockQueries = stockQueryCount();

        SqlCaptureStatementInspector.clear();
        JsonNode candidate = getJson("/items/purchase-search?name=Qx&branchId=" + data.mainBranchId(),
                data.tenantId(), data.adminToken());

        ItemApiResponseComparator.assertSameItemIds(legacy, candidate);
        for (JsonNode oldItem : legacy) {
            JsonNode newItem = ItemApiResponseComparator.itemById(candidate, oldItem.path("id").asLong());
            ItemApiResponseComparator.assertSamePurchaseFields(oldItem, newItem);
            List<String> returnedFields = new ArrayList<>();
            Iterator<String> fields = newItem.fieldNames();
            fields.forEachRemaining(returnedFields::add);
            assertThat(returnedFields).containsExactlyInAnyOrderElementsOf(List.of(
                    "id", "name", "altName", "barcode", "itemType", "costPrice", "sellingPrice",
                    "defaultUnit", "availableQty", "availableBaseQty", "hasNegativeStock"));
        }

        long candidateStockQueries = stockQueryCount();
        assertThat(legacyStockQueries).as("legacy per-item stock query count").isGreaterThan(1);
        assertThat(candidateStockQueries).as("purchase-search bulk stock query count").isEqualTo(1);
        assertThat(candidateStockQueries).isLessThan(legacyStockQueries);
    }

    @Test
    @DisplayName("manager purchase search remains pinned to the assigned branch")
    void managerCannotOverrideAssignedBranch() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("qb");
        String managerToken = login(data.tenantId(), data.tenantId() + "-manager", DEFAULT_PASSWORD);

        JsonNode result = getJson("/items/purchase-search?name=Qx&branchId=" + data.secondBranchId(),
                data.tenantId(), managerToken);
        JsonNode legacy = fixture.purchaseBaseline(data, data.mainBranchId());
        ItemApiResponseComparator.assertSameItemIds(legacy, result);
        for (JsonNode oldItem : legacy) {
            ItemApiResponseComparator.assertSamePurchaseFields(oldItem,
                    ItemApiResponseComparator.itemById(result, oldItem.path("id").asLong()));
        }

        String cashierToken = login(data.tenantId(), data.tenantId() + "-cashier", DEFAULT_PASSWORD);
        JsonNode cashierResult = getJson("/items/purchase-search?name=Qx&branchId=0",
                data.tenantId(), cashierToken);
        ItemApiResponseComparator.assertSameItemIds(legacy, cashierResult);
    }

    private long stockQueryCount() {
        return SqlCaptureStatementInspector.captured().stream()
                .map(String::toLowerCase)
                .filter(sql -> sql.contains("stock_batches"))
                .count();
    }
}
