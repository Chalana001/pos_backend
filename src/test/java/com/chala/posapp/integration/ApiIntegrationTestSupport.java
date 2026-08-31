package com.chala.posapp.integration;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.repository.BillingRecordRepository;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CashDropRepository;
import com.chala.posapp.repository.BankAccountRepository;
import com.chala.posapp.repository.CashShiftRepository;
import com.chala.posapp.repository.StockOverrideAuditRepository;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.CreditPaymentRepository;
import com.chala.posapp.repository.CustomerNoteRepository;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.ExpenseRepository;
import com.chala.posapp.repository.GrnItemRepository;
import com.chala.posapp.repository.GrnRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.OrderItemRepository;
import com.chala.posapp.repository.OrderRepository;
import com.chala.posapp.repository.PurchaseRepository;
import com.chala.posapp.repository.StockAdjustmentRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.StockTransferItemRepository;
import com.chala.posapp.repository.StockTransferRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.repository.SupplierRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.chala.posapp.tenant.CurrentTenantResolver;
import com.chala.posapp.tenant.TenantContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
abstract class ApiIntegrationTestSupport {

    protected static final String DEFAULT_PASSWORD = "Pass@123";

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    protected MockMvc mockMvc;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected SubscriptionPlanRepository subscriptionPlanRepository;

    @Autowired
    protected TenantSubscriptionRepository tenantSubscriptionRepository;

    @Autowired
    protected BranchRepository branchRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected SubCategoryRepository subCategoryRepository;

    @Autowired
    protected SupplierRepository supplierRepository;

    @Autowired
    protected ItemRepository itemRepository;

    @Autowired
    protected StockBatchRepository stockBatchRepository;

    @Autowired
    protected PurchaseRepository purchaseRepository;

    @Autowired
    protected GrnRepository grnRepository;

    @Autowired
    protected GrnItemRepository grnItemRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected OrderItemRepository orderItemRepository;

    @Autowired
    protected CashShiftRepository cashShiftRepository;

    @Autowired
    protected StockOverrideAuditRepository stockOverrideAuditRepository;

    @Autowired
    protected CashDropRepository cashDropRepository;

    @Autowired
    protected BankAccountRepository bankAccountRepository;

    @Autowired
    protected ExpenseRepository expenseRepository;

    @Autowired
    protected CustomerRepository customerRepository;

    @Autowired
    protected CustomerNoteRepository customerNoteRepository;

    @Autowired
    protected CreditPaymentRepository creditPaymentRepository;

    @Autowired
    protected StockAdjustmentRepository stockAdjustmentRepository;

    @Autowired
    protected StockTransferRepository stockTransferRepository;

    @Autowired
    protected StockTransferItemRepository stockTransferItemRepository;

    @Autowired
    protected BillingRecordRepository billingRecordRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private Filter springSecurityFilterChain;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    protected String uniqueKey(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        return prefix + "-" + suffix;
    }

    protected SubscriptionPlan ensurePlan(String name, BillingCycle billingCycle, double initialPrice, double renewalPrice, int maxBranches) {
        // Plans live in the master catalog; a bare repository call anchors on
        // the legacy database, which in the test containers has only the
        // tenant schema - prod's legacy db happens to hold everything, which
        // is why this never failed outside CI.
        return TenantContext.callWith(CurrentTenantResolver.MASTER_TENANT, () ->
                subscriptionPlanRepository.findByName(name)
                        .orElseGet(() -> subscriptionPlanRepository.save(SubscriptionPlan.builder()
                                .name(name)
                                .billingCycle(billingCycle)
                                .initialPrice(initialPrice)
                                .renewalPrice(renewalPrice)
                                .maxBranches(maxBranches)
                                .build())));
    }

    protected TenantFixture seedTenantShop(String tenantId, int maxBranches) {
        SubscriptionPlan plan = ensurePlan("TEST_PLAN_" + tenantId.toUpperCase(Locale.ROOT), BillingCycle.MONTHLY, 2000.0, 2000.0, maxBranches);

        String adminUsername = tenantId + "-admin";
        String managerUsername = tenantId + "-manager";
        String cashierUsername = tenantId + "-cashier";

        TenantSubscription subscription = TenantContext.callWith(CurrentTenantResolver.MASTER_TENANT, () ->
                tenantSubscriptionRepository.save(TenantSubscription.builder()
                .tenantId(tenantId)
                .shopName("Shop " + tenantId)
                .adminUsername(adminUsername)
                .plan(plan)
                .isActive(true)
                .blocked(false)
                .extraBranches(0)
                .validUntil(LocalDateTime.now().plusDays(30))
                .notes("integration seed")
                .build()));

        Branch mainBranch = new Branch();
        mainBranch.setCode(tenantId.toUpperCase(Locale.ROOT).replace("-", "_"));
        mainBranch.setName("Main Branch " + tenantId);
        mainBranch.setAddress("Main address");
        mainBranch.setPhone("0770000000");
        mainBranch.setActive(true);
        mainBranch = branchRepository.save(mainBranch);

        User admin = createUser(tenantId, adminUsername, Role.ADMIN, null, DEFAULT_PASSWORD, true);
        User manager = createUser(tenantId, managerUsername, Role.MANAGER, mainBranch.getId(), DEFAULT_PASSWORD, true);
        User cashier = createUser(tenantId, cashierUsername, Role.CASHIER, mainBranch.getId(), DEFAULT_PASSWORD, true);

        return new TenantFixture(tenantId, subscription, mainBranch, admin, manager, cashier);
    }

    protected User createUser(String tenantId, String username, Role role, Long branchId, String password, boolean enabled) {
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .enabled(enabled)
                .branchId(branchId)
                .build();
        return userRepository.save(user);
    }

    protected String login(String tenantId, String username, String password) throws Exception {
        JsonNode auth = jsonRequest(
                HttpMethod.POST,
                "/auth/login",
                tenantId,
                null,
                """
                {
                  "username": "%s",
                  "password": "%s"
                }
                """.formatted(username, password),
                200
        );
        return auth.path("token").asText();
    }

    protected String loginViaApiAlias(String tenantId, String username, String password) throws Exception {
        JsonNode auth = jsonRequest(
                HttpMethod.POST,
                "/api/auth/login",
                tenantId,
                null,
                """
                {
                  "username": "%s",
                  "password": "%s"
                }
                """.formatted(username, password),
                200
        );
        return auth.path("token").asText();
    }

    protected JsonNode getJson(String path, String tenantId, String token) throws Exception {
        return jsonRequest(HttpMethod.GET, path, tenantId, token, null, 200);
    }

    protected JsonNode postJson(String path, String tenantId, String token, Object body) throws Exception {
        String jsonBody = body instanceof String str ? str : objectMapper.writeValueAsString(body);
        return jsonRequest(HttpMethod.POST, path, tenantId, token, jsonBody, 200);
    }

    protected JsonNode putJson(String path, String tenantId, String token, Object body) throws Exception {
        String jsonBody = body instanceof String str ? str : objectMapper.writeValueAsString(body);
        return jsonRequest(HttpMethod.PUT, path, tenantId, token, jsonBody, 200);
    }

    protected JsonNode patchJson(String path, String tenantId, String token, Object body) throws Exception {
        String jsonBody = body instanceof String str ? str : objectMapper.writeValueAsString(body);
        return jsonRequest(HttpMethod.PATCH, path, tenantId, token, jsonBody, 200);
    }

    protected void deleteExpectingStatus(String path, String tenantId, String token, int expectedStatus) throws Exception {
        jsonRequest(HttpMethod.DELETE, path, tenantId, token, null, expectedStatus);
    }

    protected JsonNode jsonRequest(HttpMethod method, String path, String tenantId, String token, String jsonBody, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders.request(method, path)
                .contentType(MediaType.APPLICATION_JSON);

        if (tenantId != null) {
            requestBuilder.header("X-Tenant-ID", tenantId);
        }

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        if (jsonBody != null && !jsonBody.isBlank()) {
            requestBuilder.content(jsonBody);
        }

        MvcResult result = mockMvc.perform(requestBuilder)
                .andExpect(status().is(expectedStatus))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            return TextNode.valueOf(content);
        }
    }

    protected StockBatch firstBatchFor(Long branchId, Long itemId) {
        return stockBatchRepository.findByBranchIdAndItemId(branchId, itemId).stream()
                .findFirst()
                .orElseThrow();
    }

    protected record TenantFixture(
            String tenantId,
            TenantSubscription subscription,
            Branch mainBranch,
            User admin,
            User manager,
            User cashier
    ) {
    }
}
