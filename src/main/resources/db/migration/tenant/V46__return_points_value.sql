-- V46: The part of a return's goods that had been paid for in points, in money.
--
-- cf80154 made a return refund only the cash share of the returned goods, with the points
-- share going back as points. The return receipt then has to explain why "goods returned
-- 1,180" became "refund 200", and the two figures that explain it are the points share and
-- the bill-discount share. The points count (V44) is not enough: at any rate other than one
-- rupee a point it does not add up in the money column.
--
-- Banked on the return like every other figure its receipt prints, so a reprint agrees with
-- the original. The discount share is the remainder and needs no column.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V45.

DROP PROCEDURE IF EXISTS _v46_add_col;
CREATE PROCEDURE _v46_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v46_add_col('order_returns', 'loyalty_value_returned', 'DECIMAL(19,4) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v46_add_col;
