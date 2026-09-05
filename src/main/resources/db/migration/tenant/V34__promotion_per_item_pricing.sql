-- V34: A promotion can carry a different price for every item it targets.
--
-- Until now an ITEM promotion had one discount_type and one discount_value shared by every
-- item in it, so "Christmas sale: 20 items at 20 different prices" was 20 separate
-- promotions, each with its own name, date range, branch and on/off switch, kept in sync
-- by hand. Per-item pricing is how small retail actually runs a campaign.
--
-- The override lives on promotion_targets rather than in a new table because that row
-- already says "this item is in this promotion", and it is already loaded eagerly with the
-- promotion on the checkout path. A parallel table would add a join to pricing.
--
-- Resolution order, per target row:
--   1. offer_price set                  -> the item sells at exactly that price
--   2. discount_type + discount_value   -> per-item discount off list price
--   3. neither                          -> fall back to the promotion's own discount
--
-- Rule 3 is what makes this backward compatible: every existing target row has all three
-- columns NULL and keeps pricing exactly as before, so there is no backfill.
--
-- promotions gains a margin guard. Typing a price directly makes selling below cost easy —
-- 39.90 instead of 399.00 — and items already carry cost_price, so the check is free.
-- allow_below_cost exists because a loss leader is a legitimate campaign; the flag is how
-- an operator says the price is deliberate.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31/V32/V33.

DROP PROCEDURE IF EXISTS _v34_add_col;
CREATE PROCEDURE _v34_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v34_add_col('promotion_targets', 'offer_price',    'DECIMAL(19,4) NULL');
CALL _v34_add_col('promotion_targets', 'discount_type',  "ENUM('NONE','PERCENT','FIXED') NULL");
CALL _v34_add_col('promotion_targets', 'discount_value', 'DECIMAL(19,4) NULL');

CALL _v34_add_col('promotions', 'margin_floor_percent', 'DECIMAL(5,2) NULL');
CALL _v34_add_col('promotions', 'allow_below_cost',     'BIT(1) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v34_add_col;
