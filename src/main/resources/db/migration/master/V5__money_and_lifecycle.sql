-- V5: Trials, discount codes, invoices and renewal reminders.
--
-- Turns the billing ledger from a record of what happened into a workflow: a shop can start
-- on a trial that expires by itself, a renewal can carry a discount code, every payment can
-- produce an invoice the owner can be sent, and the shops due to lapse are a queue rather
-- than something you notice when they call.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS — same guarded procedure as V3.

DROP PROCEDURE IF EXISTS _v5_add_col;
CREATE PROCEDURE _v5_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- A plan can offer a trial; 0 means it does not.
CALL _v5_add_col('subscription_plans', 'trial_days', 'INT NOT NULL DEFAULT 0');

-- Trial state and the grace window after expiry, per shop. grace_days is why a shop can be
-- past valid_until without being cut off the same minute.
CALL _v5_add_col('tenant_subscriptions', 'is_trial',           'TINYINT(1) NOT NULL DEFAULT 0');
CALL _v5_add_col('tenant_subscriptions', 'trial_ends_at',      'DATETIME(6) NULL');
CALL _v5_add_col('tenant_subscriptions', 'grace_days',         'INT NOT NULL DEFAULT 0');
CALL _v5_add_col('tenant_subscriptions', 'last_reminder_type', 'VARCHAR(20) NULL');
CALL _v5_add_col('tenant_subscriptions', 'last_reminder_at',   'DATETIME(6) NULL');

-- The ledger keeps recording the NET amount in `amount` so every existing report stays
-- correct; gross and discount are added alongside it rather than redefining it.
CALL _v5_add_col('billing_records', 'gross_amount',    'DOUBLE NOT NULL DEFAULT 0');
CALL _v5_add_col('billing_records', 'discount_amount', 'DOUBLE NOT NULL DEFAULT 0');
CALL _v5_add_col('billing_records', 'discount_code',   'VARCHAR(40) NULL');

DROP PROCEDURE IF EXISTS _v5_add_col;

-- Existing rows predate discounts: gross equals what was charged.
UPDATE billing_records SET gross_amount = amount WHERE gross_amount = 0;

CREATE TABLE IF NOT EXISTS `discount_codes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(40) NOT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `discount_type` ENUM('PERCENT','FIXED') NOT NULL,
  `value` DOUBLE NOT NULL,
  `valid_from` DATETIME(6) DEFAULT NULL,
  `valid_until` DATETIME(6) DEFAULT NULL,
  -- NULL means unlimited; used_count is incremented on redemption.
  `max_uses` INT DEFAULT NULL,
  `used_count` INT NOT NULL DEFAULT 0,
  -- Comma-separated plan ids, or NULL for "any plan".
  `applies_to_plan_ids` VARCHAR(255) DEFAULT NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_by` VARCHAR(80) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_discount_code` (`code`),
  KEY `idx_discount_active` (`active`, `valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `discount_redemptions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code_id` BIGINT NOT NULL,
  `code` VARCHAR(40) NOT NULL,
  `tenant_id` VARCHAR(100) NOT NULL,
  `billing_record_id` BIGINT DEFAULT NULL,
  `gross_amount` DOUBLE NOT NULL,
  `amount_off` DOUBLE NOT NULL,
  `net_amount` DOUBLE NOT NULL,
  `redeemed_by` VARCHAR(80) DEFAULT NULL,
  `redeemed_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_redemption_code` (`code_id`),
  KEY `idx_redemption_tenant` (`tenant_id`),
  CONSTRAINT `fk_redemption_code` FOREIGN KEY (`code_id`)
      REFERENCES `discount_codes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `subscription_invoices` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `invoice_no` VARCHAR(40) NOT NULL,
  `tenant_id` VARCHAR(100) NOT NULL,
  `shop_name` VARCHAR(120) NOT NULL,
  `billing_record_id` BIGINT DEFAULT NULL,
  `plan_name` VARCHAR(120) DEFAULT NULL,
  `period_start` DATE DEFAULT NULL,
  `period_end` DATE DEFAULT NULL,
  `subtotal` DOUBLE NOT NULL DEFAULT 0,
  `discount_amount` DOUBLE NOT NULL DEFAULT 0,
  `tax_amount` DOUBLE NOT NULL DEFAULT 0,
  `total` DOUBLE NOT NULL DEFAULT 0,
  `status` ENUM('ISSUED','PAID','VOID') NOT NULL DEFAULT 'ISSUED',
  `notes` VARCHAR(500) DEFAULT NULL,
  `billed_to_name` VARCHAR(160) DEFAULT NULL,
  `billed_to_email` VARCHAR(160) DEFAULT NULL,
  `billed_to_phone` VARCHAR(60) DEFAULT NULL,
  `issued_by` VARCHAR(80) DEFAULT NULL,
  `issued_at` DATETIME(6) NOT NULL,
  `paid_at` DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_no` (`invoice_no`),
  KEY `idx_invoice_tenant` (`tenant_id`, `issued_at`),
  KEY `idx_invoice_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
