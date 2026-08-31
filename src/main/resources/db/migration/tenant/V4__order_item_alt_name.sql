ALTER TABLE `order_items`
    ADD COLUMN `alt_name` VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
    AFTER `item_name`;
