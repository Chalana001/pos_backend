package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.service.ItemRequestSnapshotResolver;
import com.chala.posapp.service.PosCatalogueAssembler;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.QuantityConversionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PosCatalogueAssemblerIntegrationTest extends ApiIntegrationTestSupport {
    @Autowired private PosCatalogueAssembler assembler;
    @Autowired private ItemRepository items;
    @MockitoSpyBean private ItemRequestSnapshotResolver snapshotResolver;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private final LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);

    @Test
    @DisplayName("complete response mapping matches legacy POS at concrete, all-branch, and null scopes")
    void fullLegacyParityForAllBranchScopes() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("asm");
        for (Long branchId : java.util.Arrays.asList(data.mainBranchId(), data.secondBranchId(), 0L, null)) {
            JsonNode legacy = fixture.posBaseline(data, branchId);
            List<ItemResponse> assembled = TenantContext.callWith(data.tenantId(), () -> assembler.assemble(branchId));
            JsonNode candidate = objectMapper.valueToTree(assembled);
            List<JsonNode> legacyScoped = fixture.scopedToDataset(legacy, data);
            List<JsonNode> candidateScoped = new java.util.ArrayList<>();
            for (JsonNode item : candidate) {
                if (data.allItemIds().contains(item.path("id").asLong())) candidateScoped.add(item);
            }
            JsonNode legacyArray = objectMapper.valueToTree(legacyScoped);
            JsonNode candidateArray = objectMapper.valueToTree(candidateScoped);
            ItemApiResponseComparator.assertSameItemIds(legacyArray, candidateArray);
            for (JsonNode oldItem : legacyScoped) {
                JsonNode newItem = ItemApiResponseComparator.itemById(candidateArray, oldItem.path("id").asLong());
                ItemApiResponseComparator.assertSameItem(oldItem, newItem, Set.of());
                for (String field : List.of("createdAt", "labelExpiry", "scaleResolvedQuantity", "scaleResolvedUnit", "scaleResolvedAmount")) {
                    assertThat(oldItem.has(field)).as("legacy envelope field %s", field).isTrue();
                    assertThat(newItem.has(field)).as("assembled envelope field %s", field).isTrue();
                    if (!"createdAt".equals(field)) {
                        assertThat(newItem.get(field)).as("POS default field %s", field).isEqualTo(oldItem.get(field));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("inactive and POS-hidden candidates are rejected exactly like the legacy POS response")
    void inactiveAndHiddenEligibilityMatchesLegacy() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("asm-flags");
        TenantContext.runWith(data.tenantId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            Item inactive = newItem("Qx inactive catalogue item", "INACTIVE-" + UUID.randomUUID().toString().substring(0, 8));
            inactive.setActive(false);
            items.save(inactive);
            Item hidden = newItem("Qx POS hidden catalogue item", "HIDDEN-" + UUID.randomUUID().toString().substring(0, 8));
            hidden.setPosVisible(false);
            items.save(hidden);
            return null;
        }));

        JsonNode legacy = getJson("/items/searchForPos?name=", data.tenantId(), data.adminToken());
        List<ItemResponse> assembled = TenantContext.callWith(data.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> assembler.assemble(data.mainBranchId())));
        JsonNode candidate = objectMapper.valueToTree(assembled);
        assertThat(ItemApiResponseComparator.idSet(candidate)).containsExactlyInAnyOrderElementsOf(
                ItemApiResponseComparator.idSet(legacy).stream().filter(data.posEligibleAtMain()::contains).toList());
        assertThat(candidate).noneMatch(row -> row.path("name").asText().contains("inactive catalogue"));
        assertThat(candidate).noneMatch(row -> row.path("name").asText().contains("POS hidden catalogue"));
    }

    @Test
    @DisplayName("empty catalogue returns an empty catalogue")
    void emptyCatalogue() {
        TenantFixture tenant = seedTenantShop(uniqueKey("asm-empty"), 1);
        TenantContext.runWith(tenant.tenantId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            items.deleteAll();
            assertThat(assembler.assemble(null)).isEmpty();
            return null;
        }));
    }

    @Test
    @DisplayName("one snapshot is resolved per invocation and tenant context is restored after plan lookup")
    void snapshotResolutionAndTenantContextAreRequestLocal() {
        TenantFixture tenant = seedTenantShop(uniqueKey("asm-snapshot"), 1);
        String previous = "ASM-PREVIOUS";
        TenantContext.setTenant(previous);
        try {
            TenantContext.runWith(tenant.tenantId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
                clearInvocations(snapshotResolver);
                assembler.assemble(null);
                verify(snapshotResolver, times(1)).resolve(null);
                assertThat(TenantContext.getTenant()).isEqualTo(tenant.tenantId());
                return null;
            }));
            assertThat(TenantContext.getTenant()).isEqualTo(previous);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("zero-only and negative-only stock histories match the legacy response")
    void zeroAndNegativeOnlyStockParity() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("asm-ledger");
        Long[] ids = TenantContext.callWith(data.tenantId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            com.chala.posapp.entity.Branch branch = branchRepository.findById(data.mainBranchId()).orElseThrow();
            Item zeroOnly = items.save(newItem("Zero only history", "ZERO-" + UUID.randomUUID().toString().substring(0, 8)));
            Item negativeOnly = items.save(newItem("Negative only history", "NEG-" + UUID.randomUUID().toString().substring(0, 8)));
            stockBatchRepository.save(batch(zeroOnly, branch, 0));
            stockBatchRepository.save(batch(negativeOnly, branch, -2500));
            items.flush();
            return new Long[]{zeroOnly.getId(), negativeOnly.getId()};
        }));
        JsonNode legacy = getJson("/items/searchForPos?name=&branchId=" + data.mainBranchId(), data.tenantId(), data.adminToken());
        List<ItemResponse> assembled = TenantContext.callWith(data.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> assembler.assemble(data.mainBranchId())));
        JsonNode newRows = objectMapper.valueToTree(assembled);
        for (Long id : ids) {
            JsonNode oldItem = ItemApiResponseComparator.itemById(legacy, id);
            JsonNode newItem = ItemApiResponseComparator.itemById(newRows, id);
            ItemApiResponseComparator.assertSameItem(oldItem, newItem, Set.of());
        }
        assertThat(ItemApiResponseComparator.itemById(newRows, ids[0]).path("availableBaseQty").asInt()).isZero();
        assertThat(ItemApiResponseComparator.itemById(newRows, ids[0]).path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(ItemApiResponseComparator.itemById(newRows, ids[1]).path("availableBaseQty").asInt()).isEqualTo(-2500);
        assertThat(ItemApiResponseComparator.itemById(newRows, ids[1]).path("hasNegativeStock").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("conversion preserves the legacy integer boundaries and checked overflow behavior")
    void checkedIntegerConversionMatchesLegacyRepository() {
        ConversionService converter = DefaultConversionService.getSharedInstance();
        assertThat(convertTotal((long) Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(convertTotal((long) Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
        assertThatThrownBy(() -> convertTotal((long) Integer.MAX_VALUE + 1))
                .isInstanceOf(ConversionFailedException.class)
                .isNotInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> convertTotal((long) Integer.MIN_VALUE - 1))
                .isInstanceOf(ConversionFailedException.class)
                .isNotInstanceOf(ArithmeticException.class);

        // The legacy repository and assembler share Spring's checked numeric conversion semantics.
        assertThat(converter.convert((long) Integer.MAX_VALUE, Integer.class)).isEqualTo(Integer.MAX_VALUE);
        assertThat(converter.convert((long) Integer.MIN_VALUE, Integer.class)).isEqualTo(Integer.MIN_VALUE);
        assertThatThrownBy(() -> converter.convert((long) Integer.MAX_VALUE + 1, Integer.class))
                .isInstanceOf(ConversionFailedException.class);
        assertThatThrownBy(() -> converter.convert((long) Integer.MIN_VALUE - 1, Integer.class))
                .isInstanceOf(ConversionFailedException.class);
    }

    @Test
    @DisplayName("small and larger candidate catalogues keep SQL count bounded")
    void queryCountDoesNotScaleWithCandidates() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("count");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        TenantContext.callWith(data.tenantId(), () -> {
            statistics.clear();
            long smallCount = new TransactionTemplate(transactionManager).execute(status -> {
                assembler.assemble(data.mainBranchId());
                return statistics.getQueryExecutionCount();
            });

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (int i = 0; i < 120; i++) {
                    items.save(newItem("Count item " + i, "COUNT-" + i));
                }
                items.flush();
            });
            statistics.clear();
            long largeCount = new TransactionTemplate(transactionManager).execute(status -> {
                assembler.assemble(data.mainBranchId());
                return statistics.getQueryExecutionCount();
            });

            assertThat(largeCount).isLessThanOrEqualTo(smallCount + 1);
            assertThat(largeCount).isLessThan(30);
            return null;
        });
    }

    @Test
    @DisplayName("aggregate integer boundaries match the legacy repository result and failures")
    void numericBoundaryParityAgainstLegacyRepository() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("numeric");
        TenantContext.callWith(data.tenantId(), () -> {
            assertBoundaryCase(data, new long[]{Integer.MAX_VALUE}, Integer.MAX_VALUE);
            assertBoundaryCase(data, new long[]{Integer.MIN_VALUE}, Integer.MIN_VALUE);
            assertBoundaryCase(data, new long[]{Integer.MAX_VALUE, 1}, null);
            assertBoundaryCase(data, new long[]{Integer.MIN_VALUE, -1}, null);
            return null;
        });
    }

    private Integer convertTotal(Long total) {
        return DefaultConversionService.getSharedInstance().convert(total, Integer.class);
    }

    private void assertBoundaryCase(LegacyItemApiFixture.Dataset data, long[] quantities, Integer expected) {
        Long itemId = new TransactionTemplate(transactionManager).execute(status -> {
            com.chala.posapp.entity.Branch branch = branchRepository.findById(data.mainBranchId()).orElseThrow();
            Item item = items.save(newItem("Numeric " + quantities[0] + " rows " + UUID.randomUUID(),
                    "NUM-" + UUID.randomUUID().toString().substring(0, 10)));
            for (long quantity : quantities) {
                stockBatchRepository.save(batch(item, branch, Math.toIntExact(quantity)));
            }
            items.flush();
            return item.getId();
        });

        Throwable legacyFailure = capture(() -> legacyTotal(data, itemId));
        Throwable assemblerFailure = capture(() -> assembleOne(data, itemId));
        if (expected != null) {
            assertThat(legacyFailure).isNull();
            assertThat(assemblerFailure).isNull();
            assertThat(legacyTotal(data, itemId)).isEqualTo(expected);
            assertThat(assembleOne(data, itemId).getAvailableBaseQty()).isEqualTo(expected);
        } else {
            assertThat(legacyFailure).isInstanceOf(ConversionFailedException.class);
            assertThat(assemblerFailure).isInstanceOf(ConversionFailedException.class)
                    .isNotInstanceOf(ArithmeticException.class);
        }
    }

    private Integer legacyTotal(LegacyItemApiFixture.Dataset data, Long itemId) {
        return TenantContext.callWith(data.tenantId(), () ->
                new TransactionTemplate(transactionManager).execute(status ->
                        stockBatchRepository.getTotalQuantityByItemAndScope(data.mainBranchId(), itemId)));
    }

    private ItemResponse assembleOne(LegacyItemApiFixture.Dataset data, Long itemId) {
        return TenantContext.callWith(data.tenantId(), () ->
                new TransactionTemplate(transactionManager).execute(status -> assembler.assemble(data.mainBranchId()).stream()
                        .filter(response -> response.getId().equals(itemId)).findFirst().orElse(null)));
    }

    private Throwable capture(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        try { callable.call(); return null; } catch (Throwable ex) { return ex; }
    }

    private Item newItem(String name, String barcode) {
        return Item.builder().name(name).barcode(barcode).costPrice(new BigDecimal("1.00"))
                .sellingPrice(new BigDecimal("2.00")).reorderLevel(0).itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS).build();
    }

    private StockBatch batch(Item item, com.chala.posapp.entity.Branch branch, int qty) {
        return StockBatch.builder().item(item).branch(branch).quantity(qty).originalQuantity(Math.abs(qty))
                .costPrice(new BigDecimal("1.00")).sellingPrice(new BigDecimal("2.00"))
                .sourceType(StockBatchSourceType.PURCHASE).batchCode(uniqueKey("NUM-BATCH")).build();
    }
}
