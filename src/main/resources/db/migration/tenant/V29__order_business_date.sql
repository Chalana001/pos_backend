-- V29: Business date on orders
--
-- Offline sales were dated at IMPORT time, not sale time. createOrderInternal
-- stamped created_at = now() for every order, imports included, so a sale made
-- during a Tuesday outage and pushed on Wednesday was booked on Wednesday by
-- every daily, date-range and Z-report. offline_sold_at held the truth and was
-- read by nothing except repairLegacyImportedCreatedAt, which is scoped to
-- clientSaleId LIKE 'legacy:%' and so never touched a normal import.
--
-- created_at now carries the moment of sale, imported_at (already present and
-- already populated) stays as the audit trail of when it arrived, and this new
-- business_date is the unambiguous reporting key that does not depend on server
-- timezone or on the time of day a shop happens to close.
--
-- MySQL 8.0 has no ADD COLUMN/ADD INDEX ... IF NOT EXISTS, so this reuses the
-- same information_schema-guarded procedure pattern as V10/V27/V28.

DROP PROCEDURE IF EXISTS _v29_add_col;
CREATE PROCEDURE _v29_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v29_add_idx;
CREATE PROCEDURE _v29_add_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v29_add_col('orders', 'business_date', 'DATE NULL');
CALL _v29_add_idx('orders', 'idx_orders_business_date', '`business_date`');
CALL _v29_add_idx('orders', 'idx_orders_branch_business_date', '`branch_id`, `business_date`');

DROP PROCEDURE IF EXISTS _v29_add_col;
DROP PROCEDURE IF EXISTS _v29_add_idx;

-- Backfill. offline_sold_at is preferred wherever it exists, so historical
-- offline imports get the date they were actually SOLD on even though their
-- created_at still holds the import time. created_at itself is deliberately
-- left alone here: rewriting the recorded creation time of orders that have
-- already been reported on would change history rather than describe it.
UPDATE `orders`
   SET `business_date` = DATE(COALESCE(`offline_sold_at`, `created_at`))
 WHERE `business_date` IS NULL
   AND COALESCE(`offline_sold_at`, `created_at`) IS NOT NULL;
