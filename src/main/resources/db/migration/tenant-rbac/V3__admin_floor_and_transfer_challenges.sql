CREATE TABLE IF NOT EXISTS `tenant_reauth_challenges` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `purpose` VARCHAR(60) NOT NULL,
    `challenge_digest` CHAR(64) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `consumed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_reauth_challenge_digest` (`challenge_digest`),
    KEY `idx_tenant_reauth_user_purpose` (`user_id`, `purpose`, `expires_at`),
    CONSTRAINT `fk_tenant_reauth_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

UPDATE `roles`
SET `role_type` = 'SYSTEM', `legacy_compatibility` = 'ADMIN'
WHERE `role_key` = 'DEFAULT_ADMIN';

UPDATE `roles`
SET `role_type` = 'SYSTEM', `legacy_compatibility` = 'MANAGER'
WHERE `role_key` = 'DEFAULT_MANAGER';

UPDATE `roles`
SET `role_type` = 'SYSTEM', `legacy_compatibility` = 'CASHIER'
WHERE `role_key` = 'DEFAULT_CASHIER';
