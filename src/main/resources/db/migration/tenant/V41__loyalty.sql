-- V41: Loyalty — points earned on a sale and spent against a later one.
--
-- Customers had no loyalty attributes at all: no points, no tier, no lifetime value. That is
-- why a "best customers" promotion had to name people by hand, and why there was nothing to
-- reward a regular with except a discount everyone else also got.
--
-- Points are earned at a rate per unit of currency, multiplied by the customer's tier, and
-- spent back at a rate per point. Two rates rather than one because they are not the same
-- number and pretending otherwise hides the margin: earning 1 point per rupee and redeeming
-- at 0.25 rupees per point is a 25% return, which the operator should be able to see and set.
--
-- loyalty_transactions is the ledger and the balance is derived from it, not typed into it:
-- loyalty_accounts.points_balance is a running total maintained alongside each row, and every
-- row records balance_after so a disputed balance can be walked back. A cancelled sale
-- reverses its own rows rather than deleting them.
--
-- orders carries what happened on each sale — points earned, points spent, and what the spend
-- was worth — so a receipt can be reprinted months later and still show it.
--
-- Loyalty is its own module (LOYALTY): a shop can buy promotions without it.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V40.

CREATE TABLE IF NOT EXISTS `loyalty_settings` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `enabled`               bit(1) NOT NULL DEFAULT b'0',
  `points_per_currency`   DECIMAL(12,4) NOT NULL DEFAULT 1.0000,
  `currency_per_point`    DECIMAL(12,4) NOT NULL DEFAULT 1.0000,
  `min_redemption_points` int NOT NULL DEFAULT 0,
  `max_redemption_percent` DECIMAL(5,2) NULL,
  `round_earned_down`     bit(1) NOT NULL DEFAULT b'1',
  `updated_by`            bigint NULL,
  `updated_at`            datetime(6) NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `loyalty_tiers` (
  `id`                  bigint NOT NULL AUTO_INCREMENT,
  `name`                varchar(60) NOT NULL,
  `min_lifetime_points` int NOT NULL DEFAULT 0,
  `earn_multiplier`     DECIMAL(6,3) NOT NULL DEFAULT 1.000,
  `sort_order`          int NOT NULL DEFAULT 0,
  `active`              bit(1) NOT NULL DEFAULT b'1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_loyalty_tier_name` (`name`),
  KEY `idx_tenant_loyalty_tier_threshold` (`min_lifetime_points`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `loyalty_accounts` (
  `id`              bigint NOT NULL AUTO_INCREMENT,
  `customer_id`     bigint NOT NULL,
  `points_balance`  int NOT NULL DEFAULT 0,
  `lifetime_points` int NOT NULL DEFAULT 0,
  `tier_id`         bigint NULL,
  `updated_at`      datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_loyalty_account_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `loyalty_transactions` (
  `id`             bigint NOT NULL AUTO_INCREMENT,
  `customer_id`    bigint NOT NULL,
  `order_id`       bigint NULL,
  `type`           ENUM('EARN','REDEEM','ADJUST','REVERSAL') NOT NULL,
  `points`         int NOT NULL,
  `balance_after`  int NOT NULL,
  `note`           varchar(255) NULL,
  `user_id`        bigint NULL,
  `at`             datetime(6) NOT NULL,
  `reversed_at`    datetime(6) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_loyalty_txn_customer` (`customer_id`, `at`),
  KEY `idx_tenant_loyalty_txn_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS _v41_add_col;
CREATE PROCEDURE _v41_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v41_add_col('orders', 'loyalty_points_earned',   'INT NOT NULL DEFAULT 0');
CALL _v41_add_col('orders', 'loyalty_points_redeemed', 'INT NOT NULL DEFAULT 0');
CALL _v41_add_col('orders', 'loyalty_discount_amount', 'DECIMAL(19,4) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v41_add_col;
