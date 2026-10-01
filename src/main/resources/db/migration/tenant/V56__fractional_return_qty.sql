-- V56: Let sale returns carry fractional display quantities.
--
-- order_return_items.return_qty held INT because every returnable quantity was assumed to be a
-- whole piece. WEIGHT and VOLUME goods are not: a customer can bring back 0.7 KG of a 2 KG sale,
-- and the request, the entity and the schema all rejected or truncated that figure. The display
-- quantity needs three decimals, the same precision the till already accepts on a sale.
--
-- The column is a display figure only; stock arithmetic keeps using normalized base units derived
-- by QuantityConversionUtil, so 0.7 KG still restores 700 base units.
--
-- MySQL 8.0 has no ALTER TABLE ... MODIFY IF EXISTS, so this reuses the information_schema-guarded
-- stored-procedure pattern of V10/V27/V28/V31-V52. Re-running is a no-op, and a database that
-- somehow already has the DECIMAL column is left exactly as it is.
--
-- The next migration version is V56 because V54 and V55 were already taken on this branch; this
-- task was planned before those migrations landed.

DROP PROCEDURE IF EXISTS _v56_alter_return_qty;
CREATE PROCEDURE _v56_alter_return_qty()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'order_return_items'
          AND COLUMN_NAME = 'return_qty'
          AND DATA_TYPE = 'int'
    ) THEN
        ALTER TABLE `order_return_items`
            MODIFY COLUMN `return_qty` DECIMAL(12,3) NOT NULL;
    END IF;
END;

CALL _v56_alter_return_qty();

DROP PROCEDURE IF EXISTS _v56_alter_return_qty;
