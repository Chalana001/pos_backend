CREATE TABLE `reorder_plans` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `version` BIGINT NOT NULL DEFAULT 0,
    `name` VARCHAR(255) NOT NULL, `branch_id` BIGINT NULL, `status` VARCHAR(30) NOT NULL,
    `forecast_days` INT NOT NULL, `target_cover_days` INT NOT NULL,
    `created_by_user_id` BIGINT NOT NULL, `created_by_username` VARCHAR(100) NOT NULL,
    `created_at` DATETIME(3) NOT NULL, `submitted_at` DATETIME(3) NULL,
    `approved_at` DATETIME(3) NULL, `approved_by_username` VARCHAR(100) NULL,
    `rejected_at` DATETIME(3) NULL, `reject_reason` VARCHAR(500) NULL,
    `converted_at` DATETIME(3) NULL, `notes` VARCHAR(500) NULL,
    PRIMARY KEY (`id`), INDEX `idx_reorder_plan_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `reorder_plan_lines` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `plan_id` BIGINT NOT NULL,
    `item_id` BIGINT NOT NULL, `item_name` VARCHAR(255) NOT NULL, `unit` VARCHAR(20) NULL,
    `supplier_id` BIGINT NULL, `supplier_name` VARCHAR(255) NULL,
    `suggested_qty` DECIMAL(19,3) NOT NULL, `approved_qty` DECIMAL(19,3) NOT NULL,
    `unit_cost` DECIMAL(19,3) NOT NULL, `confidence` VARCHAR(20) NOT NULL,
    `excluded` BIT(1) NOT NULL DEFAULT 0, `manually_edited` BIT(1) NOT NULL DEFAULT 0,
    `edit_note` VARCHAR(255) NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_reorder_plan_item` (`plan_id`, `item_id`),
    INDEX `idx_reorder_line_supplier` (`plan_id`, `supplier_id`),
    CONSTRAINT `fk_reorder_line_plan` FOREIGN KEY (`plan_id`) REFERENCES `reorder_plans` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
