-- V61: shop-level switch for below-cost promotions.
--
-- The per-promotion "allow below cost" flag stays; it is the deliberate escape hatch for one
-- loss-leader campaign. What was missing is the owner's decision that this shop prices below
-- cost as a matter of course — small retail in particular re-prices seasonal stock below what
-- it cost whenever cash flow matters more than margin. With the switch off nothing changes:
-- below cost is refused unless the campaign itself says otherwise. With it on, the guard is
-- lifted for every campaign, the same way the campaign flag lifts it for one.
--
-- Uses the information_schema-guarded procedure pattern (tenant V50 is the reference) because
-- MySQL 8 has no ADD COLUMN IF NOT EXISTS.

DROP PROCEDURE IF EXISTS _v61_promotion_settings_allow_below_cost;
CREATE PROCEDURE _v61_promotion_settings_allow_below_cost()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'promotion_settings'
          AND COLUMN_NAME = 'allow_below_cost_promotions'
    ) THEN
        ALTER TABLE `promotion_settings`
            ADD COLUMN `allow_below_cost_promotions` TINYINT(1) NOT NULL DEFAULT 0
            AFTER `cashier_manual_stacking`;
    END IF;
END;

CALL _v61_promotion_settings_allow_below_cost();

DROP PROCEDURE IF EXISTS _v61_promotion_settings_allow_below_cost;
