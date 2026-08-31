-- V7: Rename discount_codes.value → discount_amount_value.
--
-- `value` is a reserved word in H2, which the Testcontainers-less test profile uses with
-- Hibernate's schema generation. MySQL accepts it unquoted so V5 worked in dev and would have
-- worked in production, but the table silently failed to create under test — meaning any test
-- touching discount codes would have failed for a reason that had nothing to do with the test.
--
-- V5 is already applied, so this is a new migration rather than an edit to it.

DROP PROCEDURE IF EXISTS _v7_rename_col;
CREATE PROCEDURE _v7_rename_col()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'discount_codes'
          AND COLUMN_NAME = 'value'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'discount_codes'
          AND COLUMN_NAME = 'discount_amount_value'
    ) THEN
        ALTER TABLE `discount_codes` CHANGE COLUMN `value` `discount_amount_value` DOUBLE NOT NULL;
    END IF;
END;

CALL _v7_rename_col();
DROP PROCEDURE IF EXISTS _v7_rename_col;
