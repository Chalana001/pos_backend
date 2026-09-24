-- V54: a held table remembers that the cashier took the offers off a line.
--
-- A table draft stores the cashier's own discount and nothing else, because the promotion is
-- re-priced when the table is finally settled (it may have ended since). That reasoning breaks
-- for a line the cashier deliberately excluded: without a column for it, resuming the table
-- re-applies the very offer they took off, and the guest is charged a price the till no longer
-- showed. The flag has to ride with the draft.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the information_schema-guarded
-- procedure pattern of V10/V27/V28/V31-V52.

DROP PROCEDURE IF EXISTS _v54_add_col;
CREATE PROCEDURE _v54_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v54_add_col('pending_order_items', 'exclude_promotions', 'TINYINT(1) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v54_add_col;
