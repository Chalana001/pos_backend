-- V45: template_type stops being a MySQL ENUM.
--
-- V1 declared it enum('A4','KOT','THERMAL'). Adding RETURN to PrintTemplateType made the
-- application accept a fourth type that the column would not store: saving a return layout
-- failed with "Data truncated for column 'template_type'", while reading one worked, because
-- a missing row is answered with defaults and never inserted. So the Return tab loaded, let a
-- shop lay the slip out, and quietly refused to keep it.
--
-- VARCHAR rather than a longer ENUM. The entity has always mapped this as
-- @Enumerated(STRING) with length 20 — the ENUM was the odd one out, and the next template
-- type should not need a migration to exist. The strings are unchanged, so every existing row
-- carries over as itself and the unique key on (branch_id, template_type) still holds.
--
-- Guarded on the column's current type so a database already carrying VARCHAR is left alone.

DROP PROCEDURE IF EXISTS _v45_widen_template_type;
CREATE PROCEDURE _v45_widen_template_type()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'receipt_template_settings'
          AND COLUMN_NAME = 'template_type'
          AND DATA_TYPE = 'enum'
    ) THEN
        ALTER TABLE `receipt_template_settings`
            MODIFY COLUMN `template_type` VARCHAR(20)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;
    END IF;
END;

CALL _v45_widen_template_type();

DROP PROCEDURE IF EXISTS _v45_widen_template_type;
