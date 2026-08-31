-- ---------- V17__barcode_label_printer.sql ----------
-- Adds direct (local print-agent) printer settings to barcode label settings.

ALTER TABLE `barcode_label_settings`
    ADD COLUMN `direct_print_enabled` TINYINT(1)  NOT NULL DEFAULT 0 AFTER `expiry_date_format`,
    ADD COLUMN `printer_name`         VARCHAR(160) NULL AFTER `direct_print_enabled`,
    ADD COLUMN `printer_copies`       INT          NOT NULL DEFAULT 1 AFTER `printer_name`;
