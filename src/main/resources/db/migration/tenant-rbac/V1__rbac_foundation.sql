CREATE TABLE `roles` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `role_key` VARCHAR(80) NOT NULL,
    `display_name` VARCHAR(100) NOT NULL,
    `is_system` BIT(1) NOT NULL,
    `is_protected` BIT(1) NOT NULL,
    `is_active` BIT(1) NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `created_by_user_id` BIGINT NULL,
    `updated_by_user_id` BIGINT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rbac_roles_role_key` (`role_key`),
    KEY `idx_rbac_roles_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `role_permissions` (
    `role_id` BIGINT NOT NULL,
    `permission_key` VARCHAR(120) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `changed_by_user_id` BIGINT NULL,
    PRIMARY KEY (`role_id`, `permission_key`),
    KEY `idx_rbac_role_permissions_permission` (`permission_key`, `role_id`),
    CONSTRAINT `fk_rbac_role_permissions_role`
        FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_rbac_role_permissions_changed_by`
        FOREIGN KEY (`changed_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `user_permission_overrides` (
    `user_id` BIGINT NOT NULL,
    `permission_key` VARCHAR(120) NOT NULL,
    `override_state` ENUM('ALLOW', 'DENY') NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_by_user_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `permission_key`),
    KEY `idx_rbac_user_overrides_permission` (`permission_key`, `user_id`),
    CONSTRAINT `fk_rbac_user_overrides_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_rbac_user_overrides_updated_by`
        FOREIGN KEY (`updated_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS `_rbac_add_user_columns`;
CREATE PROCEDURE `_rbac_add_user_columns`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role_id'
    ) THEN
        ALTER TABLE `users` ADD COLUMN `role_id` BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'rbac_version'
    ) THEN
        ALTER TABLE `users` ADD COLUMN `rbac_version` BIGINT NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
          AND INDEX_NAME = 'idx_users_rbac_role_id'
    ) THEN
        ALTER TABLE `users` ADD KEY `idx_users_rbac_role_id` (`role_id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.REFERENTIAL_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
          AND CONSTRAINT_NAME = 'fk_users_rbac_role'
    ) THEN
        ALTER TABLE `users`
            ADD CONSTRAINT `fk_users_rbac_role`
            FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT;
    END IF;
END;
CALL `_rbac_add_user_columns`();
DROP PROCEDURE `_rbac_add_user_columns`;

CREATE TABLE `rbac_change_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `actor_user_id` BIGINT NULL,
    `target_user_id` BIGINT NULL,
    `role_id` BIGINT NULL,
    `action` VARCHAR(40) NOT NULL,
    `permission_key` VARCHAR(120) NULL,
    `before_state` JSON NULL,
    `after_state` JSON NULL,
    `reason` VARCHAR(500) NULL,
    `request_id` VARCHAR(120) NULL,
    `source_ip` VARCHAR(45) NULL,
    `user_agent` VARCHAR(512) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_rbac_audit_actor_created` (`actor_user_id`, `created_at`),
    KEY `idx_rbac_audit_target_created` (`target_user_id`, `created_at`),
    KEY `idx_rbac_audit_role_created` (`role_id`, `created_at`),
    KEY `idx_rbac_audit_created` (`created_at`),
    CONSTRAINT `fk_rbac_audit_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_rbac_audit_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_rbac_audit_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
