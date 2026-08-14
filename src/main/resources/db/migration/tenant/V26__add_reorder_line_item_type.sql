ALTER TABLE `reorder_plan_lines`
    ADD COLUMN `item_type` VARCHAR(20) NOT NULL DEFAULT 'NORMAL' AFTER `unit`;
