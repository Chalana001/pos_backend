ALTER TABLE `report_export_jobs`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 AFTER `id`;
