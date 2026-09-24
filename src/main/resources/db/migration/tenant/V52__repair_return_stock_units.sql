-- V52: Put back the stock that earlier returns failed to restore, and mark the rows repaired.
--
-- OrderReturnService compared the cashier's DISPLAY quantity (10 pcs) against OrderItem.qty,
-- which is in NORMALIZED base units (10 pcs = 10000), and then fed the same display figure
-- into the batch restore. Every return therefore put back roughly a thousandth of the goods:
-- a return of 10 pieces restored 10 base units, one hundredth of a piece, which is the
-- "stock only rises by 0.001 after a return" a shop reported. The service now normalizes the
-- quantity at the boundary, so new returns are correct; this migration repairs the returns
-- that already happened.
--
-- The buggy amount is exactly recomputable, so no guesswork is involved. For each return line:
--
--   buggy_i = LEAST( CEILING(return_qty        / order_items.qty * usage.quantity), usage.quantity )
--   right_i = LEAST( CEILING(return_qty*factor / order_items.qty * usage.quantity), usage.quantity )
--
-- where factor converts the display figure back to base units, and return_qty is the display
-- quantity stored on the row. The batch is topped up by (right_i - buggy_i), which is never
-- negative because normalized is always >= display. Lines with no usage rows (legacy rows, or
-- a single-batch sale) restore into order_items.batch_id instead, off by the same factor.
--
-- stock_restore_repaired on order_return_items makes this a once-per-row repair, so a rerun is
-- a no-op and a return made after the fix is never touched. It is a data-repair marker and is
-- deliberately not mapped on the entity.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the information_schema-guarded
-- procedure pattern of V10/V27/V28/V31-V51.

DROP PROCEDURE IF EXISTS _v52_add_col;
CREATE PROCEDURE _v52_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v52_add_col('order_return_items', 'stock_restore_repaired', 'TINYINT(1) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v52_add_col;

-- Every row already in the table was written by the buggy path, so they all start at 0 and
-- are picked up below. Rows written after this migration are created with the default 0 too,
-- but their restore is already correct; the procedure only ever runs once, here.

DROP PROCEDURE IF EXISTS _v52_repair_return_stock;
CREATE PROCEDURE _v52_repair_return_stock()
BEGIN
    DECLARE done          INT DEFAULT 0;
    DECLARE v_ri_id       BIGINT;
    DECLARE v_order_item  BIGINT;
    DECLARE v_return_qty  INT;
    DECLARE v_oi_qty      INT;
    DECLARE v_batch_id    BIGINT;
    DECLARE v_qty_unit    VARCHAR(10);
    DECLARE v_item_type   VARCHAR(20);
    DECLARE v_factor      INT;
    DECLARE v_normalized  INT;
    DECLARE v_usages      INT DEFAULT 0;

    DECLARE cur CURSOR FOR
        SELECT ri.id, ri.order_item_id, ri.return_qty,
               oi.qty, oi.batch_id, oi.qty_unit, i.item_type
        FROM order_return_items ri
        JOIN order_items oi ON oi.id = ri.order_item_id
        JOIN items       i  ON i.id  = oi.item_id
        WHERE ri.stock_reversed = 1
          AND ri.stock_restore_repaired = 0;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_ri_id, v_order_item, v_return_qty, v_oi_qty,
                       v_batch_id, v_qty_unit, v_item_type;
        IF done = 1 THEN
            LEAVE read_loop;
        END IF;

        IF v_item_type NOT IN ('SERVICE', 'RECIPE') AND v_oi_qty > 0 THEN

            -- Display units back to base units: PCS/KG/L are stored in thousandths, G/ML as-is.
            -- NORMAL and RECIPE lines carry PCS, WEIGHT lines KG or G, VOLUME lines L or ML.
            SET v_factor = CASE WHEN v_qty_unit IN ('PCS', 'KG', 'L') THEN 1000 ELSE 1 END;
            SET v_normalized = v_return_qty * v_factor;

            SELECT COUNT(*) INTO v_usages
            FROM order_item_stock_usages WHERE order_item_id = v_order_item;

            IF v_usages > 0 THEN
                -- Top up every batch this line drew from by the shortfall on that batch.
                UPDATE stock_batches sb
                JOIN order_item_stock_usages u ON u.batch_id = sb.id
                SET sb.quantity = sb.quantity
                    + LEAST(CEILING(v_normalized / v_oi_qty * u.quantity), u.quantity)
                    - LEAST(CEILING(v_return_qty  / v_oi_qty * u.quantity), u.quantity)
                WHERE u.order_item_id = v_order_item;
            ELSEIF v_batch_id IS NOT NULL THEN
                -- No usage rows: the single-batch fallback added the display figure directly.
                UPDATE stock_batches
                SET quantity = quantity + (v_normalized - v_return_qty)
                WHERE id = v_batch_id;
            END IF;
        END IF;

        UPDATE order_return_items SET stock_restore_repaired = 1 WHERE id = v_ri_id;
    END LOOP;
    CLOSE cur;
END;

CALL _v52_repair_return_stock();

DROP PROCEDURE IF EXISTS _v52_repair_return_stock;