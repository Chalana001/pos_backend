-- V48: record WHO cancelled a purchase.
--
-- The bill already says when it was cancelled and why, but not by whom — so a voided
-- purchase, which is the one kind of purchase somebody is going to ask questions about,
-- was the only document in the system that could not answer "who did this". The audit log
-- holds it (cancelPurchase and replacePurchase are both @Audited), but reading the control
-- plane to render a shop's own screen is the wrong shape; every other document here keeps
-- its actor alongside it, the way grn.created_by_user_id does.
--
-- Nullable, and left null for bills cancelled before this shipped: there is no honest value
-- to backfill, and inventing one would be worse than showing nothing.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31/V32/V47.

DROP PROCEDURE IF EXISTS _v48_add_col;
CREATE PROCEDURE _v48_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v48_add_col('purchase', 'canceled_by_user_id', 'BIGINT NULL');

DROP PROCEDURE IF EXISTS _v48_add_col;
