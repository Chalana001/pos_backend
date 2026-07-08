-- V13: Soft-delete columns for items, customers, suppliers (MISS-06)
-- Uses stored procedure helpers (same pattern as V10) for MySQL 8.0 compatibility.

DROP PROCEDURE IF EXISTS _v13_add_col;
CREATE PROCEDURE _v13_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v13_add_idx;
CREATE PROCEDURE _v13_add_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v13_add_col('items',     'deleted_at', 'DATETIME(3) NULL DEFAULT NULL');
CALL _v13_add_col('customers', 'deleted_at', 'DATETIME(3) NULL DEFAULT NULL');
CALL _v13_add_col('suppliers', 'deleted_at', 'DATETIME(3) NULL DEFAULT NULL');

CALL _v13_add_idx('items',     'idx_items_deleted_at',     '`deleted_at`');
CALL _v13_add_idx('customers', 'idx_customers_deleted_at', '`deleted_at`');
CALL _v13_add_idx('suppliers', 'idx_suppliers_deleted_at', '`deleted_at`');

DROP PROCEDURE IF EXISTS _v13_add_col;
DROP PROCEDURE IF EXISTS _v13_add_idx;
