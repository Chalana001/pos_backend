-- V6: Announcements into the POS app, per-shop maintenance mode, platform settings.
--
-- Announcements are the platform's only way to say anything to every shop at once — a
-- planned outage, a price change, a new feature. They are targeted rather than broadcast:
-- ALL, one PLAN, one TENANT, or shops that have a given MODULE, so a message about
-- purchasing does not reach shops that cannot buy.

CREATE TABLE IF NOT EXISTS `announcements` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(160) NOT NULL,
  `body` TEXT NOT NULL,
  `severity` ENUM('INFO','WARNING','CRITICAL') NOT NULL DEFAULT 'INFO',
  -- Audience: ALL | PLAN | TENANT | MODULE. audience_value holds the plan id,
  -- tenant id or module key; ignored for ALL.
  `audience` VARCHAR(20) NOT NULL DEFAULT 'ALL',
  `audience_value` VARCHAR(255) DEFAULT NULL,
  `active_from` DATETIME(6) DEFAULT NULL,
  `active_until` DATETIME(6) DEFAULT NULL,
  `dismissible` TINYINT(1) NOT NULL DEFAULT 1,
  `published` TINYINT(1) NOT NULL DEFAULT 0,
  `link_url` VARCHAR(500) DEFAULT NULL,
  `link_label` VARCHAR(80) DEFAULT NULL,
  `created_by` VARCHAR(80) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_announcement_live` (`published`, `active_from`, `active_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- One row per shop that has dismissed a dismissible announcement.
CREATE TABLE IF NOT EXISTS `announcement_dismissals` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `announcement_id` BIGINT NOT NULL,
  `tenant_id` VARCHAR(100) NOT NULL,
  `dismissed_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dismissal` (`announcement_id`, `tenant_id`),
  CONSTRAINT `fk_dismissal_announcement` FOREIGN KEY (`announcement_id`)
      REFERENCES `announcements` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Simple key/value store. Deliberately not a one-column-per-setting table: settings get
-- added often and a migration per checkbox is not worth it.
CREATE TABLE IF NOT EXISTS `platform_settings` (
  `setting_key` VARCHAR(80) NOT NULL,
  `setting_value` TEXT,
  `updated_by` VARCHAR(80) DEFAULT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS _v6_add_col;
CREATE PROCEDURE _v6_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- Maintenance mode is distinct from blocked: blocked means "you have not paid",
-- maintenance means "we are working on your data, come back shortly".
CALL _v6_add_col('tenant_subscriptions', 'maintenance_mode',    'TINYINT(1) NOT NULL DEFAULT 0');
CALL _v6_add_col('tenant_subscriptions', 'maintenance_message', 'VARCHAR(500) NULL');

DROP PROCEDURE IF EXISTS _v6_add_col;
