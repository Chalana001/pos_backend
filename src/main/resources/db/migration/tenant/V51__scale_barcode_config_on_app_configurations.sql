-- V51: scale barcode decoding is part of App Configuration, and its digits
-- now say what they mean.
--
-- V30 put the scale barcode layout (prefix + item code + value + check digit)
-- on barcode_label_settings, next to the label designer. That was the wrong
-- home: the scale prints its own labels, the designer only prints ours, and an
-- admin looking for "how does my scale talk to the POS" looks under App
-- Configuration with the other weight-item switches. The columns move to
-- app_configurations (one row per branch plus a global default row, the same
-- fallback the other settings there already use) and gain what V30 lacked:
--
--   scale_barcode_weight_unit         G or KG, what a WEIGHT value is measured in
--   scale_barcode_value_decimals      implied decimal places in the value digits
--   scale_barcode_strip_leading_zeros PLU 00123 matches item barcode 123
--
-- scale_barcode_prefix is 40 characters so it can hold a comma separated list,
-- letters allowed ("20,21,22" or "NS"). The value type is WEIGHT / PRICE rather
-- than V30's WEIGHT_GRAMS / PRICE_CENTS, since the unit and decimals are now
-- their own columns; V30 rows are carried across with the same meaning
-- (WEIGHT_GRAMS becomes WEIGHT in G with 0 decimals, PRICE_CENTS becomes PRICE
-- with 2 decimals). A branch that had scale settings but no app_configurations
-- row of its own gets one, so nothing a shop configured is lost. The V30
-- columns are then dropped from barcode_label_settings so there is one place
-- to read them from.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded stored-procedure pattern as V10/V27/V28/V30. Every
-- step is idempotent so a rerun is a no-op. No reserved words are used as
-- column names.

DROP PROCEDURE IF EXISTS _v51_add_col;
CREATE PROCEDURE _v51_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v51_add_col('app_configurations', 'scale_barcode_enabled', 'BOOLEAN NOT NULL DEFAULT FALSE');
CALL _v51_add_col('app_configurations', 'scale_barcode_preset_key', 'VARCHAR(50) NULL');
CALL _v51_add_col('app_configurations', 'scale_barcode_prefix', 'VARCHAR(40) NULL');
CALL _v51_add_col('app_configurations', 'scale_barcode_prefix_length', 'INT NOT NULL DEFAULT 2');
CALL _v51_add_col('app_configurations', 'scale_barcode_item_code_length', 'INT NOT NULL DEFAULT 5');
CALL _v51_add_col('app_configurations', 'scale_barcode_value_length', 'INT NOT NULL DEFAULT 5');
CALL _v51_add_col('app_configurations', 'scale_barcode_value_type', "VARCHAR(20) NOT NULL DEFAULT 'WEIGHT'");
CALL _v51_add_col('app_configurations', 'scale_barcode_weight_unit', "VARCHAR(5) NOT NULL DEFAULT 'G'");
CALL _v51_add_col('app_configurations', 'scale_barcode_value_decimals', 'INT NOT NULL DEFAULT 0');
CALL _v51_add_col('app_configurations', 'scale_barcode_strip_leading_zeros', 'BOOLEAN NOT NULL DEFAULT FALSE');
CALL _v51_add_col('app_configurations', 'scale_barcode_has_check_digit', 'BOOLEAN NOT NULL DEFAULT TRUE');

DROP PROCEDURE IF EXISTS _v51_add_col;

-- Carry V30's per-branch settings across, then drop them. Guarded on the old
-- column still existing, so a rerun after the drop does nothing.
DROP PROCEDURE IF EXISTS _v51_move_scale_settings;
CREATE PROCEDURE _v51_move_scale_settings()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'barcode_label_settings'
          AND COLUMN_NAME = 'scale_barcode_enabled'
    ) THEN
        -- Branches whose scale settings would otherwise have nowhere to go.
        -- Every other app_configurations column has a default, so a minimal
        -- row is a valid one.
        INSERT INTO `app_configurations` (`branch_id`, `created_at`, `updated_at`)
        SELECT b.`branch_id`, NOW(6), NOW(6)
        FROM `barcode_label_settings` b
        LEFT JOIN `app_configurations` ac ON ac.`branch_id` = b.`branch_id`
        WHERE ac.`id` IS NULL AND b.`scale_barcode_enabled` = TRUE;

        UPDATE `app_configurations` ac
        JOIN `barcode_label_settings` b ON b.`branch_id` = ac.`branch_id`
        SET ac.`scale_barcode_enabled` = b.`scale_barcode_enabled`,
            ac.`scale_barcode_preset_key` = b.`scale_barcode_preset_key`,
            ac.`scale_barcode_prefix` = b.`scale_barcode_prefix`,
            ac.`scale_barcode_prefix_length` = b.`scale_barcode_prefix_length`,
            ac.`scale_barcode_item_code_length` = b.`scale_barcode_item_code_length`,
            ac.`scale_barcode_value_length` = LEAST(b.`scale_barcode_value_length`, 12),
            ac.`scale_barcode_value_type` = CASE b.`scale_barcode_value_type`
                                                WHEN 'PRICE_CENTS' THEN 'PRICE'
                                                ELSE 'WEIGHT'
                                            END,
            ac.`scale_barcode_weight_unit` = 'G',
            ac.`scale_barcode_value_decimals` = CASE b.`scale_barcode_value_type`
                                                    WHEN 'PRICE_CENTS' THEN 2
                                                    ELSE 0
                                                END,
            ac.`scale_barcode_strip_leading_zeros` = FALSE,
            ac.`scale_barcode_has_check_digit` = b.`scale_barcode_has_check_digit`;

        ALTER TABLE `barcode_label_settings`
            DROP COLUMN `scale_barcode_enabled`,
            DROP COLUMN `scale_barcode_preset_key`,
            DROP COLUMN `scale_barcode_prefix`,
            DROP COLUMN `scale_barcode_prefix_length`,
            DROP COLUMN `scale_barcode_item_code_length`,
            DROP COLUMN `scale_barcode_value_length`,
            DROP COLUMN `scale_barcode_value_type`,
            DROP COLUMN `scale_barcode_has_check_digit`;
    END IF;
END;

CALL _v51_move_scale_settings();

DROP PROCEDURE IF EXISTS _v51_move_scale_settings;
