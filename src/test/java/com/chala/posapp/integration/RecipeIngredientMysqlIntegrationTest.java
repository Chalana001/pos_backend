package com.chala.posapp.integration;

import com.chala.posapp.dto.item.RecipeIngredientValue;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.RecipeIngredient;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.service.RecipeIngredientReader;
import com.chala.posapp.service.RecipeIngredientResult;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-008B MySQL verification of the bulk recipe ingredient reader
 * ({@code RecipeIngredientReader} over
 * {@code RecipeIngredientRepository.findIngredientLinksForRecipes} +
 * {@code ItemRepository.findIngredientDataByIdIn}) against real MySQL 8
 * through Testcontainers.
 *
 * <p>This class is where the H2 suite cannot reach: physical tenant
 * isolation. Two real tenant databases are provisioned with
 * {@code TenantProvisioningService}, given OVERLAPPING recipe, ingredient
 * and relationship ids, and each reader call must return only the current
 * tenant's recipe and ingredient information — never a combination.
 *
 * <p>Skip policy: {@code @Testcontainers(disabledWithoutDocker = true)} is
 * the only disable path and it fires ONLY when Docker is absent — with
 * Docker available every test here executes (or fails); nothing can
 * silently skip. If Docker is unavailable in another environment this
 * verification is PENDING, never described as passed
 * (see {@code RecipeIngredientMysqlSuiteGuardTest}).
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RecipeIngredientMysqlIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            // pos_db must exist before the app boots: raw connections anchor on
            // it and the tenant migration chain runs over it.
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        // root, not the container user: the app provisions tenant databases at
        // runtime and the URL carries createDatabaseIfNotExist, both of which
        // need server-wide rights the per-database app user does not have.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private RecipeIngredientRepository recipeIngredientRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private RecipeIngredientReader reader;

    @Autowired
    private TenantProvisioningService tenantProvisioningService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // 0. Environment: these tests really ran against MySQL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the suite is talking to real MySQL 8 — a silent skip or an H2 fallback cannot pass")
    void environmentIsRealMySql() {
        assertThat(mysql.isRunning()).isTrue();
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertThat(version)
                .as("this class must not silently pass on H2 or without a container")
                .startsWith("8.");
    }

    // ---------------------------------------------------------------
    // 1. Bulk answers equal the legacy per-item reads on MySQL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("reader data equals the legacy per-recipe mapping for every recipe on MySQL")
    void readerMatchesLegacyMappingOnMySql() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item milk = newIngredient("Milk", ItemType.VOLUME, MeasurementUnit.L);
        Item recipe = newRecipe("Omelette");
        Item emptyRecipe = newRecipe("Plain");
        link(recipe, egg, 2000);
        link(recipe, cheese, 100);
        link(recipe, milk, 2000);

        RecipeIngredientResult result = reader.loadForRecipes(
                List.of(recipe.getId(), emptyRecipe.getId()));

        // Legacy side: findByParentItemId + the current mapping behavior.
        List<RecipeIngredient> legacyRows =
                recipeIngredientRepository.findByParentItemId(recipe.getId());
        List<RecipeIngredientValue> values = result.ingredientsFor(recipe.getId());
        assertThat(values).hasSize(legacyRows.size());

        for (RecipeIngredientValue value : values) {
            RecipeIngredientValue legacy = legacyFor(legacyRows, value.ingredientItemId());
            assertThat(value.baseQuantity()).isEqualTo(legacy.baseQuantity());
            assertThat(value.quantity()).isEqualByComparingTo(legacy.quantity());
            assertThat(value.qtyUnit()).isEqualTo(legacy.qtyUnit());
            assertThat(value.ingredientName()).isEqualTo(legacy.ingredientName());
            assertThat(value.ingredientBarcode()).isEqualTo(legacy.ingredientBarcode());
        }
        // Empty recipe: empty list, never null.
        assertThat(result.ingredientsFor(emptyRecipe.getId())).isEmpty();
        assertThat(result.ingredientsFor(emptyRecipe.getId())).isNotNull();

        // Characterized conversion values hold on MySQL.
        assertThat(find(values, egg.getId()).baseQuantity()).isEqualTo(2000);
        assertThat(find(values, cheese.getId()).quantity()).isEqualByComparingTo(new BigDecimal("0.1"));
    }

    // ---------------------------------------------------------------
    // 2. Tenant isolation with overlapping recipe and ingredient ids
    // ---------------------------------------------------------------

    @Test
    @DisplayName("two tenant databases with overlapping recipe and ingredient ids never see each other's data")
    void tenantIsolationWithOverlappingIds() {
        // Two REAL tenant databases (pos_<slug>), provisioned through the
        // production path. Fresh schemas give both the same auto-increment
        // ids — the overlap is asserted below, which is what makes a leak
        // detectable.
        String tenantA = uniqueKey("reci-a");
        String tenantB = uniqueKey("reci-b");
        tenantProvisioningService.provisionTenantDatabase(tenantA);
        tenantProvisioningService.provisionTenantDatabase(tenantB);

        // Tenant A: recipe 1 → ingredient 2 (base 2000) + ingredient 3 (base 100).
        long[] a = seedTenantRecipe(tenantA, new int[]{2000, 100});
        // Tenant B: same recipe/ingredient ids, DIFFERENT quantities and a
        // different ingredient name so a leak is visible in every field.
        long[] b = seedTenantRecipe(tenantB, new int[]{500, 700});

        long recipeA = a[0];
        long recipeB = b[0];
        long firstIngredientA = a[1];
        long firstIngredientB = b[1];
        long secondIngredientA = a[2];
        long secondIngredientB = b[2];

        // The ids really overlap across tenants — the isolation assertion
        // below would be meaningless otherwise.
        assertThat(recipeB).isEqualTo(recipeA);
        assertThat(firstIngredientB).isEqualTo(firstIngredientA);
        assertThat(secondIngredientB).isEqualTo(secondIngredientA);

        RecipeIngredientResult resultA = TenantContext.callWith(tenantA,
                () -> reader.loadForRecipes(List.of(recipeA)));
        RecipeIngredientResult resultB = TenantContext.callWith(tenantB,
                () -> reader.loadForRecipes(List.of(recipeB)));

        // Each tenant sees exactly its own rows and quantities.
        List<RecipeIngredientValue> valuesA = resultA.ingredientsFor(recipeA);
        List<RecipeIngredientValue> valuesB = resultB.ingredientsFor(recipeB);
        assertThat(valuesA).hasSize(2);
        assertThat(valuesB).hasSize(2);

        assertThat(find(valuesA, firstIngredientA).baseQuantity()).isEqualTo(2000);
        assertThat(find(valuesA, secondIngredientA).baseQuantity()).isEqualTo(100);
        assertThat(find(valuesB, firstIngredientB).baseQuantity()).isEqualTo(500);
        assertThat(find(valuesB, secondIngredientB).baseQuantity()).isEqualTo(700);

        // Metadata is the current row of the CURRENT tenant — tenant A's
        // first ingredient is "Tenant A ingredient 0", never B's name.
        assertThat(find(valuesA, firstIngredientA).ingredientName())
                .isEqualTo("Tenant A ingredient 0");
        assertThat(find(valuesB, firstIngredientB).ingredientName())
                .isEqualTo("Tenant B ingredient 0");
    }

    /**
     * Seeds one tenant database: two ingredient items (ids 1 and 2 in every
     * fresh schema), one recipe (id 1), and one relationship row per
     * ingredient with the given base quantities. Returns
     * {recipeId, firstIngredientId, secondIngredientId}. Everything runs
     * inside the tenant context so writes land in that tenant's catalog.
     */
    private long[] seedTenantRecipe(String tenantId, int[] baseQuantities) {
        String label = tenantId.startsWith("reci-a") ? "Tenant A" : "Tenant B";
        return TenantContext.callWith(tenantId, () -> {
            Item first = newIngredient(label + " ingredient 0", ItemType.NORMAL, MeasurementUnit.PCS);
            Item second = newIngredient(label + " ingredient 1", ItemType.WEIGHT, MeasurementUnit.KG);
            Item recipe = newRecipe(label + " recipe");
            link(recipe, first, baseQuantities[0]);
            link(recipe, second, baseQuantities[1]);
            return new long[]{recipe.getId(), first.getId(), second.getId()};
        });
    }

    // ---------------------------------------------------------------
    // 3. Query counts and empty behavior
    // ---------------------------------------------------------------

    @Test
    @DisplayName("many recipes cost two bulk queries on MySQL and result access runs zero SQL")
    void queryCountsAreBoundedOnMySql() {
        List<Long> recipeIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Item ingredient = newIngredient("Ingredient " + i, ItemType.NORMAL, MeasurementUnit.PCS);
            Item recipe = newRecipe("Recipe " + i);
            link(recipe, ingredient, 2000);
            recipeIds.add(recipe.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        RecipeIngredientResult result = reader.loadForRecipes(recipeIds);
        assertThat(statistics.getQueryExecutionCount())
                .as("5 recipes must cost two queries, not 5 or 10")
                .isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

        statistics.clear();
        for (Long recipeId : recipeIds) {
            for (RecipeIngredientValue value : result.ingredientsFor(recipeId)) {
                value.toResponse();
            }
        }
        assertThat(statistics.getQueryExecutionCount()).isZero();

        statistics.clear();
        assertThat(reader.loadForRecipes(List.of()).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    // ---------------------------------------------------------------
    // 4. Large ID collection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("6,001 recipe parent ids bind safely on MySQL and group correctly")
    void largeRecipeIdCollectionOnMySql() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item recipe = newRecipe("Real recipe");
        link(recipe, egg, 2000);

        List<Long> ids = new ArrayList<>();
        for (long id = 20_000_000L; id < 20_006_000L; id++) {
            ids.add(id);
        }
        ids.add(recipe.getId());

        Statistics statistics = statistics();
        statistics.clear();
        RecipeIngredientResult result = reader.loadForRecipes(ids);

        assertThat(result.valuesByRecipe()).containsOnlyKeys(recipe.getId());
        assertThat(find(result.ingredientsFor(recipe.getId()), egg.getId()).baseQuantity())
                .isEqualTo(2000);
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be two queries")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Recomputes the legacy per-row mapping values for one ingredient id. */
    private RecipeIngredientValue legacyFor(List<RecipeIngredient> rows, long ingredientItemId) {
        for (RecipeIngredient row : rows) {
            if (row.getIngredientId() == ingredientItemId) {
                Item item = itemRepository.findById(row.getIngredientId()).orElseThrow();
                return new RecipeIngredientValue(
                        item.getId(),
                        item.getBarcode(),
                        item.getName(),
                        com.chala.posapp.util.QuantityConversionUtil.toDisplayQuantity(item, row.getQuantity()),
                        row.getQuantity(),
                        item.getDefaultUnit());
            }
        }
        throw new AssertionError("ingredient " + ingredientItemId + " missing from legacy rows");
    }

    private RecipeIngredientValue find(List<RecipeIngredientValue> values, long ingredientItemId) {
        for (RecipeIngredientValue value : values) {
            if (value.ingredientItemId() == ingredientItemId) {
                return value;
            }
        }
        throw new AssertionError("ingredient " + ingredientItemId + " missing from reader output");
    }

    private Item newIngredient(String name, ItemType itemType, MeasurementUnit defaultUnit) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("MYRI"))
                .name(name)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .reorderLevel(0)
                .itemType(itemType)
                .defaultUnit(defaultUnit)
                .build());
    }

    private Item newRecipe(String name) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("MYRR"))
                .name(name)
                .costPrice(new BigDecimal("0.00"))
                .sellingPrice(new BigDecimal("50.00"))
                .reorderLevel(0)
                .itemType(ItemType.RECIPE)
                .build());
    }

    /** Seeds one relationship row exactly as the table stores it. */
    private void link(Item recipe, Item ingredient, int baseQuantity) {
        recipeIngredientRepository.save(RecipeIngredient.builder()
                .parentItemId(recipe.getId())
                .ingredientId(ingredient.getId())
                .quantity(baseQuantity)
                .build());
    }
}
