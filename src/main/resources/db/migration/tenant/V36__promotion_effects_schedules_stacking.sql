-- V36: Promotions learn what kind of offer they are, when in the week they run, and how
-- they combine.
--
-- Until now every promotion was one mechanic: a percentage or an amount off. The four
-- mechanics shops actually ask for — a fixed price on a category, buy-two-get-one, quantity
-- breaks, and combo bundles — could not be expressed at all. effect_type names the mechanic;
-- the existing scope + targets still say what it applies to.
--
--   DISCOUNT           the old behaviour: discount_type/discount_value off list      (default)
--   FIXED_PRICE        every targeted item sells at discount_value
--   BUY_X_GET_Y_FREE   per line: buy buy_qty, get get_qty of the same item free
--   TIERED             quantity breaks (line) or spend ladders (bill), rows in promotion_tiers
--   BUNDLE             any buy_qty eligible units for discount_value, cart-level
--   CHEAPEST_FREE      every buy_qty eligible units, the cheapest one free, cart-level
--
-- Every existing row defaults to DISCOUNT and keeps pricing exactly as before; nothing is
-- rewritten.
--
-- promotion_schedules restricts a promotion to days of the week and a time window inside
-- its date range. A promotion with no rows runs whenever its dates say so, which is what
-- every existing promotion does today. days_of_week is a bitmask, Monday = 1 through
-- Sunday = 64, 0 meaning every day; a window whose end is before its start runs overnight.
--
-- stacking_mode says how a promotion combines with others on the same line or bill.
-- BEST_ONLY is the old behaviour — the single largest discount wins. STACKABLE applies in
-- addition to the winner. EXCLUSIVE, if it wins the line, stops anything else stacking on
-- that line and blocks bill-level promotions on that order. allow_manual_stacking, when
-- off, stops a cashier's line discount from stacking on top of this promotion.
--
-- version is JPA optimistic locking: two managers editing the same promotion no longer
-- silently overwrite each other.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V35.

DROP PROCEDURE IF EXISTS _v36_add_col;
CREATE PROCEDURE _v36_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v36_add_col('promotions', 'effect_type',
    "ENUM('DISCOUNT','FIXED_PRICE','BUY_X_GET_Y_FREE','TIERED','BUNDLE','CHEAPEST_FREE') NOT NULL DEFAULT 'DISCOUNT'");
CALL _v36_add_col('promotions', 'buy_qty',  'DECIMAL(12,3) NULL');
CALL _v36_add_col('promotions', 'get_qty',  'DECIMAL(12,3) NULL');
CALL _v36_add_col('promotions', 'stacking_mode',
    "ENUM('BEST_ONLY','STACKABLE','EXCLUSIVE') NOT NULL DEFAULT 'BEST_ONLY'");
CALL _v36_add_col('promotions', 'allow_manual_stacking', 'BIT(1) NOT NULL DEFAULT 1');
CALL _v36_add_col('promotions', 'version', 'BIGINT NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v36_add_col;

CREATE TABLE IF NOT EXISTS `promotion_tiers` (
  `id`             bigint NOT NULL AUTO_INCREMENT,
  `promotion_id`   bigint NOT NULL,
  `min_qty`        DECIMAL(12,3) NULL,
  `min_amount`     DECIMAL(19,4) NULL,
  `discount_type`  ENUM('FIXED','NONE','PERCENT') NOT NULL,
  `discount_value` DECIMAL(19,4) NOT NULL,
  `sort_order`     int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_promotion_tier_promotion` (`promotion_id`),
  CONSTRAINT `fk_promotion_tier_promotion` FOREIGN KEY (`promotion_id`) REFERENCES `promotions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `promotion_schedules` (
  `id`            bigint NOT NULL AUTO_INCREMENT,
  `promotion_id`  bigint NOT NULL,
  `days_of_week`  int NOT NULL DEFAULT 0,
  `start_time`    TIME NULL,
  `end_time`      TIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_promotion_schedule_promotion` (`promotion_id`),
  CONSTRAINT `fk_promotion_schedule_promotion` FOREIGN KEY (`promotion_id`) REFERENCES `promotions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
