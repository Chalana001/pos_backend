-- ---------- V16__barcode_label_expiry.sql ----------
-- Adds batch-level (FEFO) expiry date display options to barcode label settings.

ALTER TABLE `barcode_label_settings`
    ADD COLUMN `show_expiry`        TINYINT(1)   NOT NULL DEFAULT 0 AFTER `footer_font_size`,
    ADD COLUMN `expiry_prefix`      VARCHAR(20)  NOT NULL DEFAULT 'EXP:' AFTER `show_expiry`,
    ADD COLUMN `expiry_font_size`   INT          NOT NULL DEFAULT 7 AFTER `expiry_prefix`,
    ADD COLUMN `expiry_date_format` VARCHAR(20)  NOT NULL DEFAULT 'dd/MM/yyyy' AFTER `expiry_font_size`;
