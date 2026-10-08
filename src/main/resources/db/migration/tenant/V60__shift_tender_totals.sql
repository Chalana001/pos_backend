-- V60: Per-tender and credit-collection totals on a shift.
--
-- Why: cash_shifts only recorded cash sales. Card and bank sales were invisible on
-- the shift (yet wrongly counted as cash by the old close-time recompute), and cash
-- received while settling an old credit balance never appeared anywhere on the
-- shift even though it physically entered the drawer.
--
-- card_sales / bank_sales: non-cash tender taken during this shift (informational;
-- they do NOT enter Expected Cash).
-- cash_collections: cash received during this shift settling credit dues from
-- earlier sales (this DOES physically enter the drawer; the close-time expected
-- cash formula in ShiftService deliberately does not include it yet — see the
-- service comment — but the number is now tracked so it is visible and auditable).
--
-- Historical rows are backfilled to 0: the per-event data needed to reconstruct
-- the split was never recorded, so 0 is the honest answer rather than an invented
-- approximation.
--
-- MySQL 8 has no ADD COLUMN ... IF NOT EXISTS, so this uses the same
-- information_schema-guarded stored-procedure pattern as V10/V27/V28.

DROP PROCEDURE IF EXISTS _v60_add_col;
CREATE PROCEDURE _v60_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v60_add_col('cash_shifts', 'card_sales',        'DOUBLE NULL');
CALL _v60_add_col('cash_shifts', 'bank_sales',        'DOUBLE NULL');
CALL _v60_add_col('cash_shifts', 'cash_collections',  'DOUBLE NULL');

UPDATE `cash_shifts`
SET `card_sales`       = IFNULL(`card_sales`, 0),
    `bank_sales`       = IFNULL(`bank_sales`, 0),
    `cash_collections` = IFNULL(`cash_collections`, 0);

DROP PROCEDURE IF EXISTS _v60_add_col;
