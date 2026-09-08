-- V44: What a return did to a customer's points, kept on the return.
--
-- V42 made returns give the points back; the return receipt could not say so. The customer is
-- handed a slip that accounts for the money and stays silent about the points, which is the
-- half of the transaction they cannot check for themselves.
--
-- Three numbers, because a return moves points in two directions. A partial return takes back
-- what the returned goods earned. A full return is the sale undone, so it also gives back
-- points the customer spent on it. Netting the two into one figure would print "points: 0" on
-- a return that moved four hundred of them each way.
--
-- The balance is the one the return left, banked here for the same reason the sale banks its
-- own: a reprint has to agree with the slip it copies.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V43.

DROP PROCEDURE IF EXISTS _v44_add_col;
CREATE PROCEDURE _v44_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v44_add_col('order_returns', 'loyalty_points_taken_back', 'INT NOT NULL DEFAULT 0');
CALL _v44_add_col('order_returns', 'loyalty_points_given_back', 'INT NOT NULL DEFAULT 0');
CALL _v44_add_col('order_returns', 'loyalty_points_balance', 'INT NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v44_add_col;
