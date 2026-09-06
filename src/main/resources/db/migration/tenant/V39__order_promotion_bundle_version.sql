-- V39: An imported offline sale records which promotion bundle the till priced it with.
--
-- Until now an offline till could not apply promotions at all: it had no rule set, so it
-- charged list price and the import banked exactly that. The till now carries a signed
-- snapshot of the running promotions — the bundle — and prices with the same engine, ported.
-- On import the server trusts the till's arithmetic (the customer already paid it and holds
-- the receipt) and records the bundle version here, so a sale priced with a stale bundle is
-- visible in reporting rather than silently corrected into a different total.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V38.

DROP PROCEDURE IF EXISTS _v39_add_col;
CREATE PROCEDURE _v39_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v39_add_col('orders', 'promotion_bundle_version', 'VARCHAR(40) NULL');

DROP PROCEDURE IF EXISTS _v39_add_col;
