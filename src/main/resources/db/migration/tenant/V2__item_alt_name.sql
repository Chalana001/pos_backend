ALTER TABLE `items`
    ADD COLUMN `alt_name` VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
    AFTER `name`;

CREATE INDEX `idx_items_alt_name` ON `items`(`alt_name`);
