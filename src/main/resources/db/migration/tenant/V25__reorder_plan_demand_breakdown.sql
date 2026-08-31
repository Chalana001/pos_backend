ALTER TABLE `reorder_plan_lines`
    ADD COLUMN `direct_demand_base` BIGINT NOT NULL DEFAULT 0 AFTER `approved_qty`,
    ADD COLUMN `recipe_demand_base` BIGINT NOT NULL DEFAULT 0 AFTER `direct_demand_base`,
    ADD COLUMN `total_demand_base` BIGINT NOT NULL DEFAULT 0 AFTER `recipe_demand_base`,
    ADD COLUMN `suggested_qty_base` BIGINT NOT NULL DEFAULT 0 AFTER `total_demand_base`,
    ADD COLUMN `approved_qty_base` BIGINT NOT NULL DEFAULT 0 AFTER `suggested_qty_base`;
