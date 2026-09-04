-- V33: Promotions are soft-deleted instead of removed.
--
-- orders.bill_promotion_id and order_items.promotion_id are plain bigint columns with
-- no foreign key to promotions, so the old hard DELETE succeeded and left every past
-- sale pointing at a row that no longer existed. The promotion effectiveness report
-- (RPT-08) LEFT JOINs promotions, so those orders reported a null discount type and
-- value for good — the campaign's own terms were gone while the discounts it gave
-- stayed on the books.
--
-- deleted_at retires a promotion without destroying what it did: the row stays
-- joinable for reporting, and every read path filters deleted_at IS NULL so a retired
-- promotion never prices another sale.
--
-- The active-promotion lookup runs on every checkout and every cart preview, so it
-- gets an index that leads with the two columns it now filters on equality.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31/V32.

DROP PROCEDURE IF EXISTS _v33_add_col;
CREATE PROCEDURE _v33_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v33_add_idx;
CREATE PROCEDURE _v33_add_idx(tbl VARCHAR(64), idx VARCHAR(64), idx_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` ', idx_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v33_add_col('promotions', 'deleted_at', 'DATETIME(6) NULL');

CALL _v33_add_idx('promotions', 'idx_tenant_promotion_live',
                  '(`active`, `deleted_at`, `start_at`, `end_at`)');

DROP PROCEDURE IF EXISTS _v33_add_col;
DROP PROCEDURE IF EXISTS _v33_add_idx;
