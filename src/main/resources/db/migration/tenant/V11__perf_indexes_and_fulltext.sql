-- V11: Performance indexes + FULLTEXT search + return table indexes
-- MySQL 8.0 does NOT support ADD INDEX IF NOT EXISTS or ADD FULLTEXT INDEX IF NOT EXISTS.
-- Solution: stored procedure helpers that check information_schema before acting.

-- ─────────────────────────────────────────────────────────────────────────────
-- Helper: create a regular index if it does not already exist
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v11_add_idx;
CREATE PROCEDURE _v11_add_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
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
-- Helper: create a FULLTEXT index if it does not already exist
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v11_add_ft;
CREATE PROCEDURE _v11_add_ft(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD FULLTEXT INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- ─── PERF-03: items.sub_category_id ─────────────────────────────────────────
CALL _v11_add_idx('items',               'idx_items_sub_category_id',          '`sub_category_id`');

-- ─── PERF-04: return table composite indexes ─────────────────────────────────
CALL _v11_add_idx('order_returns',       'idx_or_branch_status_date',          '`branch_id`, `status`, `created_at`');
CALL _v11_add_idx('purchase_returns',    'idx_pr_branch_status_date',          '`branch_id`, `status`, `created_at`');

-- ─── PERF-05: return item FK indexes ─────────────────────────────────────────
CALL _v11_add_idx('order_return_items',  'idx_ori_return_id',                  '`order_return_id`');
CALL _v11_add_idx('purchase_return_items', 'idx_pri_return_id',                '`purchase_return_id`');

-- ─── PERF-06/07: orders.customer_id ──────────────────────────────────────────
CALL _v11_add_idx('orders',             'idx_orders_customer_id',              '`customer_id`');

-- ─── PERF-07: grn branch + received_at ───────────────────────────────────────
CALL _v11_add_idx('grn',               'idx_grn_branch_received',             '`branch_id`, `received_at`');

-- ─── PERF-08: FULLTEXT indexes for POS and customer search ───────────────────
CALL _v11_add_ft('items',              'ft_items_name_barcode',               '`name`, `barcode`');
CALL _v11_add_ft('customers',          'ft_customers_name_phone',             '`name`, `phone`');

-- ─── BONUS: stock_adjustments and stock_transfers indexes ────────────────────
CALL _v11_add_idx('stock_adjustments', 'idx_sadj_branch_date',               '`branch_id`, `created_at`');
CALL _v11_add_idx('stock_transfers',   'idx_stransfer_branch_status_date',   '`from_branch_id`, `status`, `requested_at`');

-- ─────────────────────────────────────────────────────────────────────────────
-- Cleanup helpers
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v11_add_idx;
DROP PROCEDURE IF EXISTS _v11_add_ft;
