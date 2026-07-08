-- MISS-03: Audit trail — immutable log of every sensitive write operation.
-- Written by AuditAspect after each @Audited service method returns successfully.

CREATE TABLE IF NOT EXISTS `audit_logs` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `actor_username` VARCHAR(100) NOT NULL,
    `actor_user_id`  BIGINT,
    `actor_role`     VARCHAR(30),
    `entity_type`    VARCHAR(50)  NOT NULL,
    `entity_id`      BIGINT,
    `action`         VARCHAR(50)  NOT NULL,
    `branch_id`      BIGINT,
    `summary`        VARCHAR(500),
    `ip_address`     VARCHAR(50),
    `performed_at`   DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_audit_actor_ts`  (`actor_user_id`, `performed_at`),
    INDEX `idx_audit_entity`    (`entity_type`, `entity_id`),
    INDEX `idx_audit_action_ts` (`action`, `performed_at`),
    INDEX `idx_audit_branch_ts` (`branch_id`, `performed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
