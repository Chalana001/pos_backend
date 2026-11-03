-- V69: link purchase-created stock adjustments to their purchase and reversal.
--
-- A YES purchase clears each negative row through stock_adjustments, but those
-- historical rows were indistinguishable from a manual adjustment. Cancellation
-- therefore had to guess by reason text, which could reverse an unrelated
-- operator adjustment. These nullable links make the ownership exact:
--
--   purchase_id                purchase that created the adjustment
--   stock_batch_id             exact batch cleared by the adjustment
--   reversed_by_adjustment_id  the compensating row that reversed it
--   reversal_of_adjustment_id  set on that compensating row
--
-- Existing rows intentionally remain NULL: historical values cannot prove which
-- purchase created them, and they must not be guessed or rewritten.
--
-- MySQL 8 has no ADD COLUMN ... IF NOT EXISTS, so use the project's
-- information_schema-guarded procedure pattern.

DROP PROCEDURE IF EXISTS _v69_add_column;
CREATE PROCEDURE _v69_add_column(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _stmt FROM @sql;
        EXECUTE _stmt;
        DEALLOCATE PREPARE _stmt;
    END IF;
END;

CALL _v69_add_column('stock_adjustments', 'purchase_id', 'bigint NULL');
CALL _v69_add_column('stock_adjustments', 'stock_batch_id', 'bigint NULL');
CALL _v69_add_column('stock_adjustments', 'reversal_of_adjustment_id', 'bigint NULL');
CALL _v69_add_column('stock_adjustments', 'reversed_by_adjustment_id', 'bigint NULL');

DROP PROCEDURE IF EXISTS _v69_add_unique_index;
CREATE PROCEDURE _v69_add_unique_index(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD UNIQUE INDEX `', idx, '` (', cols, ')');
        PREPARE _stmt FROM @sql;
        EXECUTE _stmt;
        DEALLOCATE PREPARE _stmt;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v69_add_index;
CREATE PROCEDURE _v69_add_index(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE _stmt FROM @sql;
        EXECUTE _stmt;
        DEALLOCATE PREPARE _stmt;
    END IF;
END;

-- MySQL unique indexes ignore NULL, so every ordinary manual adjustment remains
-- unrestricted while an original adjustment can have at most one compensating row.
CALL _v69_add_unique_index('stock_adjustments', 'uk_stock_adjustment_reversal',
                           '`reversal_of_adjustment_id`');
CALL _v69_add_index('stock_adjustments', 'idx_stock_adjustment_purchase',
                    '`purchase_id`, `stock_batch_id`');

DROP PROCEDURE IF EXISTS _v69_add_unique_index;
DROP PROCEDURE IF EXISTS _v69_add_index;
DROP PROCEDURE IF EXISTS _v69_add_column;