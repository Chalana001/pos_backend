ALTER TABLE `reorder_plans`
    ADD COLUMN `converted_by_username` VARCHAR(100) NULL AFTER `converted_at`,
    ADD COLUMN `handoff_reference` VARCHAR(255) NULL AFTER `converted_by_username`,
    ADD INDEX `idx_reorder_plan_branch_created` (`branch_id`, `created_at`);
