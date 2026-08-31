-- V10: Performance indexes + bug fixes
-- MySQL 8.0 does NOT support:
--   • ALTER TABLE ... ADD COLUMN IF NOT EXISTS  (MariaDB only)
--   • ALTER TABLE ... ADD INDEX  IF NOT EXISTS  (MariaDB only)
--   • CREATE INDEX IF NOT EXISTS               (MySQL 9.0+ only)
-- Solution: stored procedures that check information_schema before acting.

-- ─────────────────────────────────────────────────────────────────────────────
-- Helper: add a column if it does not already exist
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v10_add_col;
CREATE PROCEDURE _v10_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- ─────────────────────────────────────────────────────────────────────────────
-- Helper: create an index if it does not already exist
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v10_add_idx;
CREATE PROCEDURE _v10_add_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Optimistic locking version column on customers
-- ─────────────────────────────────────────────────────────────────────────────
CALL _v10_add_col('customers', 'version', 'BIGINT NOT NULL DEFAULT 0');

-- ─────────────────────────────────────────────────────────────────────────────
-- 2-12. Performance indexes
-- ─────────────────────────────────────────────────────────────────────────────
CALL _v10_add_idx('orders',           'idx_orders_branch_status_date',    '`branch_id`, `status`, `created_at`');
CALL _v10_add_idx('orders',           'idx_orders_created_at',            '`created_at`');
CALL _v10_add_idx('cash_drops',       'idx_cash_drops_branch_date',       '`branch_id`, `created_at`');
CALL _v10_add_idx('expenses',         'idx_expenses_branch_date',         '`branch_id`, `created_at`');
CALL _v10_add_idx('order_items',      'idx_order_items_item_id',          '`item_id`');
CALL _v10_add_idx('customers',        'idx_customers_due_active',         '`active`, `due_amount`');
CALL _v10_add_idx('branch_sequences', 'idx_branch_sequences_branch',      '`branch_id`');
CALL _v10_add_idx('cash_shifts',      'idx_shifts_branch_cashier_status', '`branch_id`, `cashier_user_id`, `status`');
CALL _v10_add_idx('stock_batches',    'idx_batch_item_branch_qty',        '`item_id`, `branch_id`, `quantity`');
CALL _v10_add_idx('purchase',         'idx_purchase_supplier_status',     '`supplier_id`, `status`');
CALL _v10_add_idx('warranties',       'idx_warranties_order_id',          '`order_id`');

-- ─────────────────────────────────────────────────────────────────────────────
-- Cleanup helpers
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v10_add_col;
DROP PROCEDURE IF EXISTS _v10_add_idx;
