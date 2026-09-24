-- V55: a supplier may exist without a phone number.
--
-- Three layers each demanded one, so "no number" was impossible to express:
--   * this column was `varchar(20) NOT NULL` (V1 baseline);
--   * Supplier.phone is `@Column(nullable = false)`;
--   * the create form refused to submit without it.
--
-- The quirk this cleans up: the quick-add modal on the purchase screen labels the
-- field "Phone (Optional)" and sends `phone: ""` -- an empty *string*, not null.
-- The service performs no validation, so MySQL accepted `''`, which means exactly
-- ONE supplier could ever exist without a number: the second blank-phone supplier
-- hit the unique key on `phone` and came back as the generic "invalid or duplicate
-- data" 400 with no hint that the field was at fault.
--
-- The unique key itself STAYS. MySQL permits unlimited NULLs in a unique index, so
-- "many suppliers with no number" now works while two suppliers on the same real
-- number are still rejected.
--
-- MySQL 8 has no MODIFY COLUMN IF EXISTS, so this uses the information_schema-guarded
-- stored-procedure pattern from V10/V13. Copy that rather than inventing a new one.

DROP PROCEDURE IF EXISTS _v55_nullable_col;
CREATE PROCEDURE _v55_nullable_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND COLUMN_NAME = col
          AND IS_NULLABLE = 'NO'
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` MODIFY COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- 1. The column has to be nullable BEFORE the data fix below can run.
CALL _v55_nullable_col('suppliers', 'phone',
     'varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL');
CALL _v55_nullable_col('suppliers', 'email',
     'varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL');

-- 2. `''` is not "no phone number", it is a value -- and the unique key only tolerates
--    one of them. Convert every blank to a true NULL so the number of suppliers that
--    can go without one is no longer capped at one.
UPDATE suppliers SET phone = NULL WHERE phone = '';
UPDATE suppliers SET email = NULL WHERE email = '';

DROP PROCEDURE IF EXISTS _v55_nullable_col;
