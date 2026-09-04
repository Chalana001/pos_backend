-- V32: GRN lines learn about supplier free-of-charge (FOC) quantity.
--
-- Suppliers routinely hand over bonus units with a purchase ("buy 10, get 2 free").
-- Until now there was no field for them: entering the free units as a zero-cost line
-- overwrote the item's cost price with 0 (PurchaseService replaces item cost with the
-- latest effective cost), and folding them into the paid line forced the operator to
-- hand-calculate a diluted unit cost.
--
-- free_qty stores the bonus quantity in normalized base units (thousandths, same scale
-- as grn_items.qty); display_free_qty stores what the operator typed, in qty_unit.
-- Free units go into stock and dilute the line's effective unit cost
-- (net line total / (qty + free_qty)) but add nothing to the payable amount, so GRN and
-- purchase totals still match the supplier's invoice. Existing rows backfill to 0.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31.

DROP PROCEDURE IF EXISTS _v32_add_col;
CREATE PROCEDURE _v32_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v32_add_col('grn_items', 'free_qty', 'INT NOT NULL DEFAULT 0');
CALL _v32_add_col('grn_items', 'display_free_qty', 'DECIMAL(12,3) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v32_add_col;
