-- V43: The points balance a receipt should print.
--
-- V41 put what a sale did to a customer's points on the order — earned, redeemed, and what the
-- redemption was worth. What it did not keep is the balance those movements left behind, and
-- that is the one number a customer looks for on the slip: "points balance: 1,250".
--
-- Reading it from loyalty_accounts at print time would answer a different question. A receipt
-- reprinted a month later would show today's balance beside a month-old sale, and a duplicate
-- of the original slip would disagree with the original. So the balance as at the sale is
-- banked on the order, like every other number the receipt prints.
--
-- Zero for every existing row is correct rather than merely convenient: those sales were rung
-- before this column existed, and no receipt of theirs ever showed a balance.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V42.

DROP PROCEDURE IF EXISTS _v43_add_col;
CREATE PROCEDURE _v43_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v43_add_col('orders', 'loyalty_points_balance', 'INT NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v43_add_col;
