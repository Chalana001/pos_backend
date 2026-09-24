-- V53: Track stock already restored per original usage row so partial returns can apportion cumulatively.
--
-- Existing rows start at zero because the prior per-batch restore history cannot be recovered from
-- the return records. V52 has already topped up batches short-changed by the old unit bug and marked
-- those return rows. A line returned once before V52 and again afterward can therefore be restored
-- one extra time; this is accepted because the old per-batch history is unavailable to reconstruct.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so use the information_schema-guarded procedure
-- pattern used by the preceding tenant migrations.

DROP PROCEDURE IF EXISTS _v53_add_col;
CREATE PROCEDURE _v53_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v53_add_col('order_item_stock_usages', 'restored_quantity', 'INT NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v53_add_col;
