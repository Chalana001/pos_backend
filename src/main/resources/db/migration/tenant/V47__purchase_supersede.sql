-- V47: purchases can be superseded — cancelled and rebuilt as a corrected bill.
--
-- Editing a posted purchase in place is not something an auditable system should offer, so
-- a wrong bill is corrected the way accounting systems have always done it: the original is
-- voided, a replacement is issued, and the two are linked. What made that impossible here
-- was the uniqueness rule.
--
-- The supplier's invoice number belongs to the supplier. It is printed on the paper in the
-- shop's hand, and a replacement for INV-8821 is still INV-8821 — that is the whole point.
-- But UKm4hnft7g6615ig06i4msboyey made (supplier_id, invoice_no) unique across every row
-- including cancelled ones, so the cancelled original kept holding the number and the
-- replacement died on a duplicate key.
--
-- MySQL has no partial/filtered unique index, but its unique indexes ignore NULL. A
-- generated column that is NULL for anything not COMPLETED gives exactly the rule wanted:
-- one live bill per supplier+invoice, any number of cancelled ones alongside it.
--
-- Two shapes deliberately avoided, both of which MySQL accepts and H2 rejects outright:
-- `... STORED` on the column, and folding ADD UNIQUE INDEX into the same ALTER TABLE.
-- Tenant migrations do not run under the H2 test profile today (TenantFlywayRunner is
-- @Profile("!test")), but the Testcontainers suite does run them, and the portable form
-- costs nothing. VIRTUAL also needs no backfill — it is computed on read, and InnoDB
-- indexes it the same way.
--
-- MySQL 8.0 has no ADD COLUMN / DROP INDEX ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31/V32.

DROP PROCEDURE IF EXISTS _v47_add_col;
CREATE PROCEDURE _v47_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v47_drop_index;
CREATE PROCEDURE _v47_drop_index(tbl VARCHAR(64), idx VARCHAR(64))
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP INDEX `', idx, '`');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v47_add_index;
CREATE PROCEDURE _v47_add_index(tbl VARCHAR(64), idx VARCHAR(64), idx_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD ', idx_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- 1. The old all-rows constraint. Named by Hibernate in V1__baseline.sql.
--    Dropping it cannot orphan data: the replacement index below is strictly narrower,
--    and every existing row already satisfies the stricter rule.
CALL _v47_drop_index('purchase', 'UKm4hnft7g6615ig06i4msboyey');

-- 2. The supersede chain, in both directions. Nullable because almost every bill is
--    neither a replacement nor replaced; indexed because the details screen walks the
--    chain from either end.
--
--    Without these, a cancelled bill is just a cancelled bill — there is no way to tell a
--    genuine cancellation apart from a correction, which is most of the audit value the
--    feature is meant to buy.
CALL _v47_add_col('purchase', 'replaces_purchase_id', 'BIGINT NULL');
CALL _v47_add_col('purchase', 'replaced_by_purchase_id', 'BIGINT NULL');
CALL _v47_add_index('purchase', 'idx_purchase_replaces',
                    'INDEX `idx_purchase_replaces` (`replaces_purchase_id`)');
CALL _v47_add_index('purchase', 'idx_purchase_replaced_by',
                    'INDEX `idx_purchase_replaced_by` (`replaced_by_purchase_id`)');

-- 3. Uniqueness among live bills only. NULL for every cancelled row, and MySQL unique
--    indexes skip NULLs, so cancelled bills keep their real invoice numbers and never
--    collide with each other or with the replacement.
CALL _v47_add_col('purchase', 'active_invoice_key',
                  'VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN `status` = ''COMPLETED'' '
                  'THEN CONCAT(`supplier_id`, '':'', `invoice_no`) ELSE NULL END)');
CALL _v47_add_index('purchase', 'uq_purchase_active_invoice',
                    'UNIQUE INDEX `uq_purchase_active_invoice` (`active_invoice_key`)');

DROP PROCEDURE IF EXISTS _v47_add_col;
DROP PROCEDURE IF EXISTS _v47_drop_index;
DROP PROCEDURE IF EXISTS _v47_add_index;
