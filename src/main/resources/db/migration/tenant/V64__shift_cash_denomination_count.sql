-- Optional tenant-wide denomination counting for shift close. Settings are
-- stored in each tenant database; each closed shift separately keeps its own
-- denomination/quantity breakdown for audit and later review.
CREATE TABLE IF NOT EXISTS shift_cash_count_settings (
    id BIGINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    denominations_csv VARCHAR(1000) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO shift_cash_count_settings (id, enabled, denominations_csv)
SELECT 1, FALSE, '5000,2000,1000,500,100,50,20,10,5,2,1'
WHERE NOT EXISTS (SELECT 1 FROM shift_cash_count_settings WHERE id = 1);

DROP PROCEDURE IF EXISTS _v64_add_col;
CREATE PROCEDURE _v64_add_col(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END;
CALL _v64_add_col('cash_shifts', 'counted_cash_breakdown', 'LONGTEXT NULL');
DROP PROCEDURE IF EXISTS _v64_add_col;
