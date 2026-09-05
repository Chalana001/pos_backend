-- V37: Promo codes, a redemption ledger, and limits that close a promotion when spent.
--
-- Until now every promotion was automatic and open-ended. There was no way to hand a
-- customer a code, no count of how often a promotion had fired, and nothing that stopped a
-- mispriced one until a person noticed. The SaaS side of this product has had all three
-- for its own subscription discounts (discount_codes, discount_redemptions) while the shops
-- that sell with it had none.
--
-- promotion_codes: a promotion with any rows here is code-gated — it applies only when one
-- of its codes is presented at the till. code is stored uppercase and matched
-- case-insensitively because operators type them by hand. code_type PUBLIC is unlimited by
-- default, SINGLE_USE is max_redemptions = 1, PER_CUSTOMER is once per customer; the limits
-- columns are what actually enforce it and are editable regardless of type. batch_id groups
-- codes generated together so a campaign's codes can be exported or switched off as a set.
--
-- promotion_redemptions: one row per promotion per line or bill it discounted. This is what
-- makes a stacked line attributable to each promotion that priced it — order_items holds
-- one promotion_id — and what a refund reverses instead of deleting. reversed_at set means
-- the discount was given back; counts and budgets exclude those rows.
--
-- promotions gains the caps: max_total_redemptions (orders), max_redemptions_per_customer,
-- budget_amount (total discount). times_redeemed and budget_consumed are running counters
-- maintained by conditional UPDATEs inside the order transaction — never read-modify-write,
-- so two tills taking the last redemption produce exactly one winner. They are not written
-- by entity saves.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V36.

DROP PROCEDURE IF EXISTS _v37_add_col;
CREATE PROCEDURE _v37_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v37_add_col('promotions', 'max_total_redemptions',        'INT NULL');
CALL _v37_add_col('promotions', 'max_redemptions_per_customer', 'INT NULL');
CALL _v37_add_col('promotions', 'budget_amount',                'DECIMAL(19,4) NULL');
CALL _v37_add_col('promotions', 'times_redeemed',               'INT NOT NULL DEFAULT 0');
CALL _v37_add_col('promotions', 'budget_consumed',              'DECIMAL(19,4) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS _v37_add_col;

CREATE TABLE IF NOT EXISTS `promotion_codes` (
  `id`                 bigint NOT NULL AUTO_INCREMENT,
  `promotion_id`       bigint NOT NULL,
  `code`               varchar(40) NOT NULL,
  `code_type`          ENUM('PUBLIC','SINGLE_USE','PER_CUSTOMER') NOT NULL DEFAULT 'PUBLIC',
  `max_redemptions`    int NULL,
  `redemptions_used`   int NOT NULL DEFAULT 0,
  `per_customer_limit` int NULL,
  `valid_from`         datetime(6) NULL,
  `valid_to`           datetime(6) NULL,
  `active`             bit(1) NOT NULL DEFAULT b'1',
  `batch_id`           varchar(40) NULL,
  `created_at`         datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_promotion_code` (`code`),
  KEY `idx_tenant_promotion_code_promotion` (`promotion_id`),
  KEY `idx_tenant_promotion_code_batch` (`batch_id`),
  CONSTRAINT `fk_promotion_code_promotion` FOREIGN KEY (`promotion_id`) REFERENCES `promotions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `promotion_redemptions` (
  `id`                 bigint NOT NULL AUTO_INCREMENT,
  `promotion_id`       bigint NOT NULL,
  `promotion_code_id`  bigint NULL,
  `order_id`           bigint NOT NULL,
  `order_item_id`      bigint NULL,
  `item_id`            bigint NULL,
  `customer_id`        bigint NULL,
  `branch_id`          bigint NULL,
  `user_id`            bigint NULL,
  `level`              ENUM('LINE','BILL') NOT NULL,
  `discount_amount`    DECIMAL(19,4) NOT NULL,
  `redeemed_at`        datetime(6) NOT NULL,
  `reversed_at`        datetime(6) NULL,
  `reversal_order_id`  bigint NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_redemption_promotion_customer` (`promotion_id`, `customer_id`, `reversed_at`),
  KEY `idx_tenant_redemption_code_customer` (`promotion_code_id`, `customer_id`, `reversed_at`),
  KEY `idx_tenant_redemption_order` (`order_id`),
  KEY `idx_tenant_redemption_redeemed_at` (`redeemed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
