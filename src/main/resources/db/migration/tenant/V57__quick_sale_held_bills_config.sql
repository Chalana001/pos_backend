-- V57: Quick Sale held bills configuration.
--
-- A cashier can park a takeaway cart and come back to it. The records themselves
-- live only on the terminal (Dexie), so this migration is just the two switches
-- that govern them: whether the feature is on at all, and how many bills one
-- cashier may hold at this branch at the same time.
--
--   quick_sale_held_bills_enabled  tinyint(1) NOT NULL DEFAULT 1
--   quick_sale_held_bills_max      int NULL, default 2 on new rows
--
-- Max follows the same convention Promotion.maxUses uses: NULL means unlimited.
-- The service restricts it to 1, 2, 3, 5, 10, 20, 50 or unlimited, so an int is
-- plenty. Count is a branch decision, not a plan gate: every plan can hold bills.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the
-- information_schema-guarded procedure pattern of V10/V27/V28/V31-V56. Every
-- step is idempotent so a rerun is a no-op.

DROP PROCEDURE IF EXISTS _v57_add_col;
CREATE PROCEDURE _v57_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v57_add_col('app_configurations', 'quick_sale_held_bills_enabled', 'tinyint(1) NOT NULL DEFAULT 1');
CALL _v57_add_col('app_configurations', 'quick_sale_held_bills_max', 'int NULL DEFAULT 2');

DROP PROCEDURE IF EXISTS _v57_add_col;
