-- V3: Module registry + plan metadata + super admin audit log
--
-- Replaces the three hardcoded feature-gating tables that used to live in code
-- (SubscriptionFilter's *_BLOCKED_PREFIXES sets and the frontend PLAN_FEATURES
-- matrix) with a single source of truth in the control-plane database.
--
-- Resolution order for "can tenant X use module M":
--   1. tenant_modules  (explicit per-shop override, wins if present)
--   2. plan_modules    (the plan's template default)
--   3. module.default_enabled (catalog fallback for a module added after the
--      plan rows were written)
--
-- modules is a CATALOG, seeded from ModuleCatalog in code on every boot. Rows
-- are never hand-written; the code definition is authoritative for name/parent/
-- ordering, while enabled-ness lives in plan_modules / tenant_modules.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so the subscription_plans
-- additions reuse the information_schema-guarded procedure pattern from
-- V10/V27/V28 on the tenant side.

CREATE TABLE IF NOT EXISTS `modules` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `module_key` VARCHAR(60) NOT NULL,
  `parent_key` VARCHAR(60) DEFAULT NULL,
  `name` VARCHAR(120) NOT NULL,
  `description` VARCHAR(400) DEFAULT NULL,
  `category` VARCHAR(40) NOT NULL,
  `icon` VARCHAR(40) DEFAULT NULL,
  `locked` TINYINT(1) NOT NULL DEFAULT 0,
  `default_enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `display_order` INT NOT NULL DEFAULT 0,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_modules_key` (`module_key`),
  KEY `idx_modules_parent` (`parent_key`),
  KEY `idx_modules_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `plan_modules` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `module_key` VARCHAR(60) NOT NULL,
  `enabled` TINYINT(1) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_modules` (`plan_id`, `module_key`),
  KEY `idx_plan_modules_plan` (`plan_id`),
  CONSTRAINT `fk_plan_modules_plan` FOREIGN KEY (`plan_id`)
      REFERENCES `subscription_plans` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Per-shop override. A row here means "this shop deviates from its plan".
-- Deleting the row returns the shop to the plan default (reset-to-plan).
CREATE TABLE IF NOT EXISTS `tenant_modules` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(100) NOT NULL,
  `module_key` VARCHAR(60) NOT NULL,
  `enabled` TINYINT(1) NOT NULL,
  `note` VARCHAR(255) DEFAULT NULL,
  `updated_by` VARCHAR(80) DEFAULT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_modules` (`tenant_id`, `module_key`),
  KEY `idx_tenant_modules_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `super_admin_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor` VARCHAR(80) NOT NULL,
  `action` VARCHAR(60) NOT NULL,
  `target_type` VARCHAR(40) NOT NULL,
  `target_id` VARCHAR(120) DEFAULT NULL,
  `summary` VARCHAR(500) NOT NULL,
  `details` TEXT,
  `ip_address` VARCHAR(45) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_created_at` (`created_at`),
  KEY `idx_audit_actor` (`actor`),
  KEY `idx_audit_target` (`target_type`, `target_id`),
  KEY `idx_audit_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS _v3_add_col;
CREATE PROCEDURE _v3_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- Plans become first-class, editable objects: a super admin can create
-- "Bakery Pro" alongside the three seeded system plans. system_plan marks
-- FREE/STANDARD/PRO so the UI can refuse to delete them.
CALL _v3_add_col('subscription_plans', 'description',   'VARCHAR(400) NULL');
CALL _v3_add_col('subscription_plans', 'display_order', 'INT NOT NULL DEFAULT 0');
CALL _v3_add_col('subscription_plans', 'active',        'TINYINT(1) NOT NULL DEFAULT 1');
CALL _v3_add_col('subscription_plans', 'system_plan',   'TINYINT(1) NOT NULL DEFAULT 0');
CALL _v3_add_col('subscription_plans', 'color',         'VARCHAR(20) NULL');

-- Shop-level contact details the panel needs but had nowhere to store.
CALL _v3_add_col('tenant_subscriptions', 'contact_phone', 'VARCHAR(40) NULL');
CALL _v3_add_col('tenant_subscriptions', 'contact_email', 'VARCHAR(120) NULL');
CALL _v3_add_col('tenant_subscriptions', 'business_type', "VARCHAR(20) NOT NULL DEFAULT 'RETAIL'");

DROP PROCEDURE IF EXISTS _v3_add_col;

UPDATE subscription_plans SET system_plan = 1 WHERE name IN ('FREE', 'STANDARD', 'PRO');
UPDATE subscription_plans SET display_order = 1 WHERE name = 'FREE';
UPDATE subscription_plans SET display_order = 2 WHERE name = 'STANDARD';
UPDATE subscription_plans SET display_order = 3 WHERE name = 'PRO';
