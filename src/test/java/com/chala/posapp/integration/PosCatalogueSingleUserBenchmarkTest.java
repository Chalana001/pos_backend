package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disposable single-user catalogue benchmark. It measures the real authenticated endpoint
 * through MockMvc against MySQL, with one synthetic tenant and one branch containing 6,000
 * ordinary stock-tracked items. It deliberately does not exercise concurrent traffic.
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.chala.posapp.integration.SqlCaptureStatementInspector"
})
class PosCatalogueSingleUserBenchmarkTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                        + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    @DisplayName("one authenticated user loads and repeats a 6,000-item POS catalogue")
    void measuresSingleUserLargeCatalogue() throws Exception {
        String runTag = uniqueKey("catalogue-benchmark");
        TenantFixture fixture = seedTenantShop(runTag, 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName(runTag + " category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName(runTag + " subcategory");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        long startedSeed = System.nanoTime();
        for (int start = 0; start < 6000; start += 500) {
            java.util.List<Item> page = new java.util.ArrayList<>(500);
            for (int index = start; index < start + 500; index++) {
                page.add(Item.builder()
                        .barcode(runTag + "-BAR-" + index)
                        .name("Benchmark item " + index)
                        .altName("Synthetic catalogue " + index)
                        .subCategory(subCategory)
                        .costPrice(new BigDecimal("20.00"))
                        .sellingPrice(new BigDecimal("30.00"))
                        .reorderLevel(0)
                        .active(true)
                        .posVisible(true)
                        .build());
            }
            itemRepository.saveAll(page);
            itemRepository.flush();
            for (Item item : page) {
                stockBatchRepository.save(StockBatch.builder()
                        .branch(fixture.mainBranch())
                        .item(item)
                        .batchCode(runTag + "-BATCH-" + item.getBarcode())
                        .sourceType(StockBatchSourceType.PURCHASE)
                        .costPrice(new BigDecimal("20.00"))
                        .sellingPrice(new BigDecimal("30.00"))
                        .quantity(100)
                        .originalQuantity(100)
                        .build());
            }
            stockBatchRepository.flush();
        }
        long seedMillis = (System.nanoTime() - startedSeed) / 1_000_000;

        long[] timings = new long[9];
        long[] parseTimings = new long[9];
        int[] payloadBytes = new int[9];
        int[] counts = new int[9];
        Set<Long> expectedIds = new HashSet<>();
        for (Item item : itemRepository.findAll()) {
            if (item.getName().startsWith("Benchmark item ")) expectedIds.add(item.getId());
        }
        assertThat(expectedIds).hasSize(6000);

        for (int index = 0; index < timings.length; index++) {
            long started = System.nanoTime();
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(
                            "/items/pos/catalogue?branchId=" + fixture.mainBranch().getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Tenant-ID", fixture.tenantId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            timings[index] = (System.nanoTime() - started) / 1_000_000;
            String content = result.getResponse().getContentAsString();
            payloadBytes[index] = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long parseStarted = System.nanoTime();
            JsonNode response = objectMapper.readTree(content);
            parseTimings[index] = (System.nanoTime() - parseStarted) / 1_000_000;
            counts[index] = response.size();
            assertThat(counts[index]).isGreaterThanOrEqualTo(6000);
            Set<Long> actualIds = new HashSet<>();
            for (JsonNode row : response) {
                assertThat(actualIds.add(row.path("id").asLong())).isTrue();
            }
            assertThat(actualIds).containsAll(expectedIds);
        }

        System.out.printf(
                "POS_CATALOGUE_BENCHMARK tenant=%s branch=%d seedMs=%d firstMs=%d firstParseMs=%d warmMedianMs=%d warmP95Ms=%d warmMinMs=%d warmMaxMs=%d warmParseMedianMs=%d itemCount=%d payloadBytes=%d%n",
                fixture.tenantId(), fixture.mainBranch().getId(), seedMillis, timings[0], parseTimings[0],
                median(timings, 1), percentile95(timings, 1), min(timings, 1), max(timings, 1),
                median(parseTimings, 1), counts[0], payloadBytes[0]);
    }

    private long median(long[] values, int from) {
        long[] copy = java.util.Arrays.copyOfRange(values, from, values.length);
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private long percentile95(long[] values, int from) {
        long[] copy = java.util.Arrays.copyOfRange(values, from, values.length);
        java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.95) - 1)];
    }

    private long min(long[] values, int from) {
        return java.util.Arrays.stream(values, from, values.length).min().orElse(0);
    }

    private long max(long[] values, int from) {
        return java.util.Arrays.stream(values, from, values.length).max().orElse(0);
    }
}
