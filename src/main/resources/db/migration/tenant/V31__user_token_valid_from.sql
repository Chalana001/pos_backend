-- V31: Give every user a "sessions issued before this moment are dead" watermark.
--
-- A login JWT lives for 24 hours and, until now, nothing could end it early. Disabling a
-- user did cut them off (JwtAuthFilter reloads the row on every request), but changing a
-- password did not: an operator resetting a compromised shop admin's password left the
-- attacker's existing token working for the rest of its life.
--
-- token_valid_from is compared against the token's `iat`. Any token issued before it is
-- rejected, which is what makes a password reset — and an explicit logout — actually end
-- the sessions that were already open.
--
-- NULL means "never invalidated", so existing rows need no backfill.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28.

DROP PROCEDURE IF EXISTS _v31_add_col;
CREATE PROCEDURE _v31_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v31_add_col('users', 'token_valid_from', 'DATETIME(6) NULL');

DROP PROCEDURE IF EXISTS _v31_add_col;
