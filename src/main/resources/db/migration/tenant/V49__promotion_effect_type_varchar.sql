-- V49: effect_type stops being a MySQL ENUM.
--
-- V36 declared it enum('DISCOUNT','FIXED_PRICE','BUY_X_GET_Y_FREE','TIERED','BUNDLE',
-- 'CHEAPEST_FREE'). Adding a seventh mechanic to PromotionEffectType would give the
-- application a value the column refuses: saving it fails with "Data truncated for column
-- 'effect_type'", the same way the receipt template type did before V45.
--
-- VARCHAR rather than a longer ENUM, for the same reason V45 chose it: the entity has always
-- mapped this as @Enumerated(STRING), the enum was the odd one out, and the next mechanic
-- should not need a migration to exist. Every stored string carries over as itself.
--
-- Guarded on the column's current type so a database already carrying VARCHAR is left alone.

DROP PROCEDURE IF EXISTS _v49_widen_effect_type;
CREATE PROCEDURE _v49_widen_effect_type()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'promotions'
          AND COLUMN_NAME = 'effect_type'
          AND DATA_TYPE = 'enum'
    ) THEN
        ALTER TABLE `promotions`
            MODIFY COLUMN `effect_type` VARCHAR(30)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'DISCOUNT';
    END IF;
END;

CALL _v49_widen_effect_type();

DROP PROCEDURE IF EXISTS _v49_widen_effect_type;
