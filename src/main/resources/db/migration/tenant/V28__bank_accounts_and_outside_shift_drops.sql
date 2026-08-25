-- V28: Bank accounts + outside-shift cash drops
--
-- Adds a manageable "Bank Accounts" reference list (add/rename/deactivate,
-- same shape as expense_types) and lets a cash drop optionally name which
-- bank account it went to.
--
-- Also allows a cash drop to be recorded WITHOUT a shift — e.g. an owner
-- banking already-collected cash after every shift for the day is closed.
-- shift_id was NOT NULL; it becomes nullable. An outside-shift drop
-- (shift_id IS NULL) is pure record-keeping: it does NOT touch any
-- CashShift row, and is never subtracted from a shift's Expected Cash —
-- only a drop recorded from inside a live shift does that, same as before.
--
-- MySQL 8.0 has no ADD COLUMN/ADD INDEX ... IF NOT EXISTS, so this reuses the
-- same information_schema-guarded procedure pattern as V10/V27.

CREATE TABLE IF NOT EXISTS `bank_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `account_number` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `bank_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bank_account_name` (`name`),
  KEY `idx_bank_account_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS _v28_add_col;
CREATE PROCEDURE _v28_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v28_add_idx;
CREATE PROCEDURE _v28_add_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v28_add_col('cash_drops', 'bank_account_id', 'BIGINT NULL');
CALL _v28_add_idx('cash_drops', 'idx_cash_drops_bank_account', '`bank_account_id`');
CALL _v28_add_idx('cash_drops', 'idx_cash_drops_shift', '`shift_id`');

DROP PROCEDURE IF EXISTS _v28_add_col;
DROP PROCEDURE IF EXISTS _v28_add_idx;

-- MODIFY COLUMN is naturally idempotent — safe if this migration is ever
-- re-run against a database already on this shape.
ALTER TABLE `cash_drops` MODIFY COLUMN `shift_id` bigint NULL;
