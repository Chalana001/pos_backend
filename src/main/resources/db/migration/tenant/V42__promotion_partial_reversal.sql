-- V42: Returns give back the part of a promotion they undid.
--
-- Cancelling a sale reversed its promotion redemptions and its loyalty points. Returning goods
-- against an invoice reversed neither: the promotion's redemption stayed counted, its budget
-- stayed spent, and points earned on the returned goods stayed in the customer's balance. A
-- shop running a capped campaign with a normal rate of returns exhausted the cap early and had
-- no way to see why.
--
-- A return is usually partial, so "reversed or not" is the wrong shape for it. reversal_of_id
-- points a new row at the one it is giving back, and that row carries a negative
-- discount_amount. Every existing query keeps working and gets the right answer for free:
-- SUM(discount_amount) nets the refund out, and COUNT(DISTINCT order_id) still counts the
-- order once — which is correct, because a customer who returned one of three items did use
-- the promotion.
--
-- A full return is not this shape. It undoes the sale, so it takes the whole-order path a
-- cancellation already uses: originals marked reversed_at, count and budget both released.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V41.

DROP PROCEDURE IF EXISTS _v42_add_col;
CREATE PROCEDURE _v42_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v42_add_col('promotion_redemptions', 'reversal_of_id', 'BIGINT NULL');
CALL _v42_add_col('promotion_redemptions', 'order_return_id', 'BIGINT NULL');

DROP PROCEDURE IF EXISTS _v42_add_col;
