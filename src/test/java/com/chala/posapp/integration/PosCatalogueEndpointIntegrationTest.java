package com.chala.posapp.integration;

import com.chala.posapp.entity.TenantModule;
import com.chala.posapp.module.ModuleRouteResolver;
import com.chala.posapp.repository.TenantModuleRepository;
import com.chala.posapp.service.ModuleAccessService;
import com.chala.posapp.service.PosCatalogueAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PosCatalogueEndpointIntegrationTest extends ApiIntegrationTestSupport {

    private final LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);

    @Autowired
    private ModuleRouteResolver moduleRouteResolver;

    @Autowired
    private TenantModuleRepository tenantModuleRepository;

    @Autowired
    private ModuleAccessService moduleAccessService;

    @MockitoSpyBean
    private PosCatalogueAssembler assembler;

    @Test
    @DisplayName("POS catalogue route preserves full legacy response at concrete, all-branch, and null scopes")
    void endpointMatchesLegacyResponseForAllScopes() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("endpoint");

        for (Long branchId : new Long[]{data.mainBranchId(), 0L, null}) {
            JsonNode legacy = fixture.posBaseline(data, branchId);
            JsonNode candidate = getJson(cataloguePath(branchId), data.tenantId(), data.adminToken());
            assertParity(legacy, candidate, data);
        }

        verify(assembler, times(3)).assemble(org.mockito.ArgumentMatchers.any());
        assertThat(moduleRouteResolver.resolve("/items/pos/catalogue", "GET")).contains("POS");
    }

    @Test
    @DisplayName("POS catalogue requires authentication and pins non-admin users to assigned branch")
    void endpointEnforcesAuthenticationAndBranchAccess() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("auth");

        getJsonExpectingStatus("/items/pos/catalogue?branchId=" + data.mainBranchId(), data.tenantId(), null, 403);

        var tenant = userRepository.findByUsername(data.tenantId() + "-manager").orElseThrow();
        String managerToken = login(data.tenantId(), tenant.getUsername(), DEFAULT_PASSWORD);
        JsonNode ownBranch = getJson("/items/pos/catalogue", data.tenantId(), managerToken);
        assertThat(ItemApiResponseComparator.idSet(ownBranch)).containsAll(data.posEligibleAtMain());
        getJsonExpectingStatus("/items/pos/catalogue?branchId=" + data.secondBranchId(), data.tenantId(), managerToken, 400);
    }

    @Test
    @DisplayName("POS module gate denies the catalogue route when tenant override disables POS")
    void endpointEnforcesPosModuleOverride() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("module");
        tenantModuleRepository.save(TenantModule.builder()
                .tenantId(data.tenantId())
                .moduleKey("POS")
                .enabled(false)
                .build());
        moduleAccessService.invalidate(data.tenantId());

        getJsonExpectingStatus("/items/pos/catalogue?branchId=" + data.mainBranchId(), data.tenantId(), data.adminToken(), 403);
    }

    @Test
    @DisplayName("POS catalogue is isolated by tenant and returns an empty array when no items exist")
    void endpointIsTenantIsolatedAndSupportsEmptyCatalogue() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("tenant");
        JsonNode catalogue = getJson("/items/pos/catalogue?branchId=" + data.mainBranchId(), data.tenantId(), data.adminToken());
        assertThat(ItemApiResponseComparator.idSet(catalogue)).containsExactlyInAnyOrderElementsOf(data.posEligibleAtMain());

        org.mockito.Mockito.doReturn(java.util.List.of()).when(assembler).assemble(0L);
        JsonNode empty = getJson("/items/pos/catalogue?branchId=0", data.tenantId(), data.adminToken());
        assertThat(empty.isArray()).isTrue();
        assertThat(empty).isEmpty();
        verify(assembler).assemble(0L);
    }

    @Test
    @DisplayName("POS catalogue propagates assembler failures through existing exception handling")
    void endpointPreservesAssemblyExceptionHandling() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("failure");
        clearInvocations(assembler);
        org.mockito.Mockito.doThrow(new IllegalStateException("catalogue failure"))
                .when(assembler).assemble(data.mainBranchId());

        getJsonExpectingStatus("/items/pos/catalogue?branchId=" + data.mainBranchId(), data.tenantId(), data.adminToken(), 500);
        verify(assembler).assemble(data.mainBranchId());
    }

    private static String cataloguePath(Long branchId) {
        return "/items/pos/catalogue" + (branchId == null ? "" : "?branchId=" + branchId);
    }

    private void assertParity(JsonNode legacy, JsonNode candidate, LegacyItemApiFixture.Dataset data) {
        JsonNode scopedLegacy = objectMapper.valueToTree(fixture.scopedToDataset(legacy, data));
        JsonNode scopedCandidate = objectMapper.valueToTree(fixture.scopedToDataset(candidate, data));
        ItemApiResponseComparator.assertSameItemIds(scopedLegacy, scopedCandidate);
        for (JsonNode oldItem : scopedLegacy) {
            JsonNode newItem = ItemApiResponseComparator.itemById(scopedCandidate, oldItem.path("id").asLong());
            ItemApiResponseComparator.assertSameItem(oldItem, newItem, Set.of());
        }
    }
}
