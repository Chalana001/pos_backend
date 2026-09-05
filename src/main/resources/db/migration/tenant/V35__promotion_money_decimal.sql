-- V35: Promotion money columns become DECIMAL(19,4).
--
-- discount_value, min_bill_amount and max_discount_amount were DOUBLE. A double cannot hold
-- 0.1 exactly, so every percentage and every cap went into the table already slightly wrong
-- and came back out rounded. The pricing engine now computes in BigDecimal end to end; the
-- columns are the last place the value was still a binary float.
--
-- promotion_targets.offer_price / discount_value were born DECIMAL in V34, so only the
-- promotions table changes here. The matching amount columns on orders and order_items
-- (promotion_discount_amount, bill_promotion_discount_amount, promotion_discount_total) are
-- deliberately NOT touched: those are the largest tables in a tenant and MODIFY COLUMN
-- rebuilds them, which is not something to run at boot without first timing it on a
-- production-sized dump. They stay double for now and are read into BigDecimal at the edge.
--
-- MODIFY is guarded on DATA_TYPE so re-running is a no-op. MySQL widens DOUBLE -> DECIMAL in
-- place with no loss for any value that fits four decimals, which every money value does.

DROP PROCEDURE IF EXISTS _v35_to_decimal;
CREATE PROCEDURE _v35_to_decimal(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
          AND DATA_TYPE <> 'decimal'
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` MODIFY COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v35_to_decimal('promotions', 'discount_value',      'DECIMAL(19,4) NOT NULL');
CALL _v35_to_decimal('promotions', 'min_bill_amount',     'DECIMAL(19,4) NOT NULL DEFAULT 0');
CALL _v35_to_decimal('promotions', 'max_discount_amount', 'DECIMAL(19,4) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v35_to_decimal;
