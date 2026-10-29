-- V65: Stock expiry alert window configuration.
--
-- One column on app_configurations: how many calendar months ahead a batch
-- counts as "expiring soon". Null or 0 falls back to the service default (2).
-- The same value drives the Stock Inventory expiry filter and the
-- /reports/v2/expired-stock report, so there is a single source of truth for
-- the window. A branch decision, not a plan gate.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the
-- information_schema-guarded procedure pattern. Idempotent: a rerun is a no-op.

DROP PROCEDURE IF EXISTS _v65_add_col;
CREATE PROCEDURE _v65_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v65_add_col('app_configurations', 'expiry_alert_months', 'int NULL DEFAULT 2');

DROP PROCEDURE IF EXISTS _v65_add_col;
