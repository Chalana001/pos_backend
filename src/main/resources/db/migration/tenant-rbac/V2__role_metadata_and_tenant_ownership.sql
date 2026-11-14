ALTER TABLE `users`
    MODIFY COLUMN `role` ENUM('ADMIN', 'CASHIER', 'MANAGER', 'SUPER_ADMIN', 'CUSTOM') NOT NULL;

DROP PROCEDURE IF EXISTS `_rbac_v2_add_role_metadata`;
CREATE PROCEDURE `_rbac_v2_add_role_metadata`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles' AND COLUMN_NAME = 'role_type'
    ) THEN
        ALTER TABLE `roles`
            ADD COLUMN `role_type` ENUM('SYSTEM', 'CUSTOM') NOT NULL DEFAULT 'SYSTEM' AFTER `is_system`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles' AND COLUMN_NAME = 'template_role_id'
    ) THEN
        ALTER TABLE `roles` ADD COLUMN `template_role_id` BIGINT NULL AFTER `role_type`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles' AND COLUMN_NAME = 'legacy_compatibility'
    ) THEN
        ALTER TABLE `roles` ADD COLUMN `legacy_compatibility` VARCHAR(30) NULL AFTER `template_role_id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles' AND INDEX_NAME = 'idx_rbac_roles_template'
    ) THEN
        ALTER TABLE `roles` ADD KEY `idx_rbac_roles_template` (`template_role_id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.REFERENTIAL_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'roles'
          AND CONSTRAINT_NAME = 'fk_rbac_roles_template'
    ) THEN
        ALTER TABLE `roles`
            ADD CONSTRAINT `fk_rbac_roles_template`
            FOREIGN KEY (`template_role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT;
    END IF;
END;
CALL `_rbac_v2_add_role_metadata`();
DROP PROCEDURE `_rbac_v2_add_role_metadata`;

CREATE TABLE IF NOT EXISTS `tenant_ownership` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `singleton_key` VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    `owner_user_id` BIGINT NULL,
    `status` ENUM('UNRESOLVED', 'RESOLVED') NOT NULL DEFAULT 'UNRESOLVED',
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `created_by_user_id` BIGINT NULL,
    `updated_by_user_id` BIGINT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_ownership_singleton` (`singleton_key`),
    KEY `idx_tenant_ownership_owner` (`owner_user_id`),
    CONSTRAINT `fk_tenant_ownership_owner`
        FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_tenant_ownership_created_by`
        FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_tenant_ownership_updated_by`
        FOREIGN KEY (`updated_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `tenant_ownership` (`singleton_key`, `status`)
SELECT 'TENANT', 'UNRESOLVED'
WHERE NOT EXISTS (SELECT 1 FROM `tenant_ownership` WHERE `singleton_key` = 'TENANT');
