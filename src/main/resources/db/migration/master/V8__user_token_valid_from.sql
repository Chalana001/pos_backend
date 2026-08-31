-- V8: The control-plane copy of tenant V31.
--
-- The super admin lives in the master `users` table, so it needs the same session
-- watermark as every shop user — otherwise the one account that can reach every shop
-- would be the only one whose tokens could not be revoked.
--
-- See tenant/V31__user_token_valid_from.sql for what the column means.

DROP PROCEDURE IF EXISTS _v8_add_col;
CREATE PROCEDURE _v8_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v8_add_col('users', 'token_valid_from', 'DATETIME(6) NULL');

DROP PROCEDURE IF EXISTS _v8_add_col;
