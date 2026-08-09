-- ---------- V18__barcode_label_layout.sql ----------
-- Adds the ordered element-array layout (JSON) to barcode label settings.
-- NULL → fall back to the flat show*/font-size columns (legacy layout).

ALTER TABLE `barcode_label_settings`
    ADD COLUMN `layout_json` LONGTEXT NULL AFTER `printer_copies`;
