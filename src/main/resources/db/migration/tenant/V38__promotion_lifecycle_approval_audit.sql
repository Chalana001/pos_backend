-- V38: Promotions get a lifecycle, an approval gate and an audit trail.
--
-- State was one boolean. A promotion was either on or off, and nothing recorded who turned it
-- on, who changed its terms, or whether anyone but the person typing had looked at a 40% cut.
--
-- status is stored only for the states that need a human decision: DRAFT (being written),
-- PENDING_APPROVAL (waiting for a second admin), ACTIVE, PAUSED. Whether an ACTIVE promotion
-- is live right now, scheduled for later, ended, or exhausted is still derived from its dates
-- and counters at read time — the engine already checks those on every sale, so nothing has to
-- flip rows on a timer, and there is no scheduler to fall behind. active stays the engine's
-- switch and is kept consistent with status by the service.
--
-- Existing rows: active ones become ACTIVE, switched-off ones PAUSED. The UPDATE is safe to
-- re-run — a DRAFT or PENDING row is never ACTIVE, so it is never touched.
--
-- promotion_settings is one row per tenant: whether promotions above a threshold need a
-- second admin's approval, and whether a cashier may stack a manual discount on a promoted
-- line. promotion_audit records every state change and edit with who and when.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V37.

DROP PROCEDURE IF EXISTS _v38_add_col;
CREATE PROCEDURE _v38_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v38_add_col('promotions', 'status',
    "ENUM('DRAFT','PENDING_APPROVAL','ACTIVE','PAUSED') NOT NULL DEFAULT 'ACTIVE'");
CALL _v38_add_col('promotions', 'created_by',    'BIGINT NULL');
CALL _v38_add_col('promotions', 'updated_by',    'BIGINT NULL');
CALL _v38_add_col('promotions', 'submitted_by',  'BIGINT NULL');
CALL _v38_add_col('promotions', 'approved_by',   'BIGINT NULL');
CALL _v38_add_col('promotions', 'approved_at',   'DATETIME(6) NULL');
CALL _v38_add_col('promotions', 'approval_note', 'VARCHAR(255) NULL');

DROP PROCEDURE IF EXISTS _v38_add_col;

UPDATE promotions SET status = 'PAUSED' WHERE active = b'0' AND status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS `promotion_settings` (
  `id`                         bigint NOT NULL AUTO_INCREMENT,
  `approval_required`          bit(1) NOT NULL DEFAULT b'0',
  `approval_threshold_percent` DECIMAL(5,2) NULL,
  `approval_threshold_amount`  DECIMAL(19,4) NULL,
  `cashier_manual_stacking`    bit(1) NOT NULL DEFAULT b'1',
  `updated_by`                 bigint NULL,
  `updated_at`                 datetime(6) NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `promotion_audit` (
  `id`            bigint NOT NULL AUTO_INCREMENT,
  `promotion_id`  bigint NOT NULL,
  `action`        varchar(24) NOT NULL,
  `user_id`       bigint NULL,
  `username`      varchar(120) NULL,
  `note`          varchar(500) NULL,
  `at`            datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_promotion_audit_promotion` (`promotion_id`, `at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
