-- ---------- V9__currency_symbol.sql ----------
-- Adds currency_symbol column to receipt_template_settings

ALTER TABLE `receipt_template_settings`
    ADD COLUMN `currency_symbol` VARCHAR(10) NULL DEFAULT 'LKR'
        COMMENT 'Currency symbol printed on receipt (LKR, Rs, රු, etc.)' AFTER `template_lines`;
