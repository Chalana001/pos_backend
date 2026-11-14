package com.chala.posapp.rbac;

import com.chala.posapp.service.TenantDatabaseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantDatabaseRegistryCutoverTest {

    @Test
    void promotionUsesAtomicCopiedPreconditionAndExpectedDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), eq("shop-a"), eq("pos_shop_a"))).thenReturn(1);
        TenantDatabaseRegistry registry = new TenantDatabaseRegistry(jdbcTemplate);
        ReflectionTestUtils.setField(registry, "masterDb", "pos_master");

        registry.promoteCopiedToMigrated("shop-a", "pos_shop_a");

        verify(jdbcTemplate).update(
                contains("SET status = 'MIGRATED'"),
                eq("shop-a"), eq("pos_shop_a"));
        verify(jdbcTemplate).update(
                contains("WHERE tenant_id = ? AND db_name = ? AND status = 'COPIED'"),
                eq("shop-a"), eq("pos_shop_a"));
    }
}
