package com.chala.posapp.rbac.shadow;

import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacMigrationService;
import com.chala.posapp.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantRbacShadowEvaluatorTest {
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void disabledShadowModeDoesNotTouchDataSource() {
        DataSource dataSource = mock(DataSource.class);
        TenantRbacShadowEvaluator evaluator = new TenantRbacShadowEvaluator(dataSource,
                mock(TenantDatabaseRegistry.class), mock(TenantRbacMigrationService.class),
                "pos_db", "pos_master", false);

        ShadowEvaluationResult result = evaluator.evaluate("orders.create");

        assertEquals(ShadowEvaluationStatus.NOT_APPLICABLE, result.status());
        verifyNoInteractions(dataSource);
    }

    @Test
    void masterTenantIsNotEvaluatedAsTenantRbac() {
        TenantContext.setTenant("MASTER");
        TenantRbacShadowEvaluator evaluator = new TenantRbacShadowEvaluator(mock(DataSource.class),
                mock(TenantDatabaseRegistry.class), mock(TenantRbacMigrationService.class),
                "pos_db", "pos_master", true);

        ShadowEvaluationResult result = evaluator.evaluate("orders.create");

        assertEquals(ShadowEvaluationStatus.NOT_APPLICABLE, result.status());
        assertEquals(ShadowClassification.PLATFORM_ONLY, result.classification());
    }

    @Test
    void invalidPermissionIsUnmappedAndDoesNotThrow() {
        TenantRbacShadowEvaluator evaluator = new TenantRbacShadowEvaluator(mock(DataSource.class),
                mock(TenantDatabaseRegistry.class), mock(TenantRbacMigrationService.class),
                "pos_db", "pos_master", true);

        ShadowEvaluationResult result = evaluator.evaluate("unknown.permission");

        assertEquals(ShadowEvaluationStatus.UNMAPPED, result.status());
        assertEquals(ShadowDecision.UNKNOWN, result.decision());
    }
}
