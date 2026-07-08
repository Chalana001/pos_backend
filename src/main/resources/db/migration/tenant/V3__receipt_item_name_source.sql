ALTER TABLE `receipt_template_settings`
    ADD COLUMN `item_name_source` VARCHAR(20) NOT NULL DEFAULT 'PRIMARY' AFTER `receipt_font_family`;
