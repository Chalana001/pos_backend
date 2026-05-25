package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PcsBaseQuantityMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-24-pcs-base-quantity-v1";
    private static final int PCS_BASE_UNITS = 1000;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_migrations (
                    migration_key VARCHAR(120) PRIMARY KEY,
                    applied_at TIMESTAMP NOT NULL
                )
                """);

        Integer alreadyApplied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_migrations WHERE migration_key = ?",
                Integer.class,
                MIGRATION_KEY
        );
        if (alreadyApplied != null && alreadyApplied > 0) {
            return;
        }

        List<Long> normalItemIds = jdbcTemplate.query(
                "SELECT id FROM items WHERE item_type = 'NORMAL'",
                (rs, rowNum) -> rs.getLong("id")
        );
        if (normalItemIds.isEmpty()) {
            markApplied();
            return;
        }

        for (Long itemId : normalItemIds) {
            scale("items", "reorder_level", "id", itemId);
            scale("stock_batches", "quantity", "item_id", itemId);
            scale("stock_batches", "original_quantity", "item_id", itemId);
            scale("recipe_ingredients", "quantity", "ingredient_id", itemId);
            scale("order_items", "qty", "item_id", itemId);
            scale("order_item_stock_usages", "quantity", "item_id", itemId);
            scale("stock_transfer_items", "qty", "item_id", itemId);
            scale("stock_adjustments", "qty_change", "item_id", itemId);
            scale("grn_items", "qty", "item_id", itemId);
        }

        markApplied();
        log.info("Applied PCS base quantity migration for {} normal items", normalItemIds.size());
    }

    private void scale(String table, String quantityColumn, String idColumn, Long itemId) {
        jdbcTemplate.update(
                "UPDATE " + table + " SET " + quantityColumn + " = " + quantityColumn + " * ? WHERE " + idColumn + " = ?",
                PCS_BASE_UNITS,
                itemId
        );
    }

    private void markApplied() {
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
    }
}
