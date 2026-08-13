CREATE TABLE IF NOT EXISTS `report_export_jobs` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `requested_by_user_id` BIGINT NOT NULL,
    `requested_by_username` VARCHAR(100) NOT NULL,
    `report_type` VARCHAR(30) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `parameters_json` TEXT NOT NULL,
    `file_name` VARCHAR(255),
    `file_path` VARCHAR(500),
    `content_type` VARCHAR(150),
    `file_size` BIGINT,
    `error_message` VARCHAR(500),
    `created_at` DATETIME(3) NOT NULL,
    `started_at` DATETIME(3),
    `completed_at` DATETIME(3),
    PRIMARY KEY (`id`),
    INDEX `idx_report_export_owner_created` (`requested_by_user_id`, `created_at`),
    INDEX `idx_report_export_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
