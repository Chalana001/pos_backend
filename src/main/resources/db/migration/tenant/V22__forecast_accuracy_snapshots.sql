CREATE TABLE `forecast_snapshots` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `export_job_id` BIGINT NOT NULL,
    `branch_id` BIGINT NULL,
    `forecast_days` INT NOT NULL,
    `window_start` DATETIME(3) NOT NULL,
    `window_end` DATETIME(3) NOT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `evaluated_at` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_forecast_snapshot_export_job` (`export_job_id`),
    INDEX `idx_forecast_snapshot_maturity` (`evaluated_at`, `window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `forecast_snapshot_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_id` BIGINT NOT NULL,
    `item_id` BIGINT NOT NULL,
    `item_name` VARCHAR(255) NOT NULL,
    `unit` VARCHAR(20) NULL,
    `predicted_qty` DECIMAL(19,3) NOT NULL,
    `actual_qty` DECIMAL(19,3) NULL,
    `absolute_error` DECIMAL(19,3) NULL,
    `confidence` VARCHAR(20) NOT NULL,
    `scored` BIT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_forecast_snapshot_item` (`snapshot_id`, `item_id`),
    INDEX `idx_forecast_snapshot_item_parent` (`snapshot_id`),
    CONSTRAINT `fk_forecast_snapshot_item_parent` FOREIGN KEY (`snapshot_id`) REFERENCES `forecast_snapshots` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
