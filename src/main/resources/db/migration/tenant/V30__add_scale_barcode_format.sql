-- V30: Configurable scale-barcode decoding, per branch.
--
-- Different shops use different weighing-scale / label-printer devices, and
-- each prints weight- or price-embedded barcodes in its own digit layout
-- (prefix + item-code + weight-or-price + optional check digit). This adds
-- the per-branch format settings to the existing barcode_label_settings
-- table (one row per branch, see V-whatever created it) so the backend can
-- decode these generically instead of hardcoding one vendor's layout.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded stored-procedure pattern as V10/V27/V28.
--
-- None of the new column names are MySQL reserved words (all prefixed
-- scale_barcode_...), unlike the bare `value` column master V5 originally
-- shipped (see V7's rename) that MySQL accepted but H2 rejected under the
-- test profile.

DROP PROCEDURE IF EXISTS _v30_add_col;
CREATE PROCEDURE _v30_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v30_add_col('barcode_label_settings', 'scale_barcode_enabled', 'BOOLEAN NOT NULL DEFAULT FALSE');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_preset_key', 'VARCHAR(50) NULL');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_prefix', 'VARCHAR(4) NULL');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_prefix_length', 'INT NOT NULL DEFAULT 2');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_item_code_length', 'INT NOT NULL DEFAULT 5');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_value_length', 'INT NOT NULL DEFAULT 5');
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_value_type', "VARCHAR(20) NOT NULL DEFAULT 'WEIGHT_GRAMS'");
CALL _v30_add_col('barcode_label_settings', 'scale_barcode_has_check_digit', 'BOOLEAN NOT NULL DEFAULT TRUE');

DROP PROCEDURE IF EXISTS _v30_add_col;
