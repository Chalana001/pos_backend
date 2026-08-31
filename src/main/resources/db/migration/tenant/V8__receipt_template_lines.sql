-- ---------- V8__receipt_template_lines.sql ----------
-- Adds the template_lines column to receipt_template_settings
-- Stores the ordered JSON array of receipt template lines for the line-by-line editor.

ALTER TABLE `receipt_template_settings`
    ADD COLUMN `template_lines` LONGTEXT NULL COMMENT 'JSON array of ReceiptTemplateLine objects for the line-by-line editor' AFTER `item_name_source`;
