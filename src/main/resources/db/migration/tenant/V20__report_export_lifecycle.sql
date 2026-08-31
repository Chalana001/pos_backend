ALTER TABLE `report_export_jobs`
    ADD COLUMN `storage_key` VARCHAR(500) NULL AFTER `file_path`,
    ADD COLUMN `email_to` VARCHAR(320) NULL AFTER `error_message`,
    ADD COLUMN `email_delivered_at` DATETIME(3) NULL AFTER `email_to`,
    ADD COLUMN `attempt_count` INT NOT NULL DEFAULT 0 AFTER `email_delivered_at`,
    ADD COLUMN `max_attempts` INT NOT NULL DEFAULT 3 AFTER `attempt_count`,
    ADD COLUMN `next_attempt_at` DATETIME(3) NULL AFTER `max_attempts`,
    ADD COLUMN `schedule_id` BIGINT NULL AFTER `next_attempt_at`,
    ADD INDEX `idx_report_export_due` (`status`, `next_attempt_at`),
    ADD INDEX `idx_report_export_completed` (`completed_at`);

UPDATE `report_export_jobs`
SET `next_attempt_at` = `created_at`
WHERE `next_attempt_at` IS NULL;

CREATE TABLE `report_schedules` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `requested_by_user_id` BIGINT NOT NULL,
    `requested_by_username` VARCHAR(100) NOT NULL,
    `report_type` VARCHAR(30) NOT NULL,
    `parameters_json` TEXT NOT NULL,
    `frequency` VARCHAR(20) NOT NULL,
    `email_to` VARCHAR(320) NULL,
    `enabled` BIT(1) NOT NULL DEFAULT 1,
    `next_run_at` DATETIME(3) NOT NULL,
    `last_run_at` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_report_schedule_owner` (`requested_by_user_id`, `created_at`),
    INDEX `idx_report_schedule_due` (`enabled`, `next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
