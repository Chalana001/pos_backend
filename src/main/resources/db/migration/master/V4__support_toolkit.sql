-- V4: Support toolkit — impersonation sessions and per-shop support notes.
--
-- Impersonation lets an operator open a shop's own POS with a short-lived token instead of
-- asking the owner for their password. Every session is a row here, not just a log line, so
-- an issued token can be revoked before it expires and so "who was in this shop, when, and
-- could they write" is answerable after the fact.
--
-- The token carries a jti that must match token_id on a row that is neither revoked nor
-- expired; deleting or revoking the row kills the session on the next request.

CREATE TABLE IF NOT EXISTS `impersonation_sessions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `token_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(100) NOT NULL,
  `actor` VARCHAR(80) NOT NULL,
  `target_username` VARCHAR(80) NOT NULL,
  `read_only` TINYINT(1) NOT NULL DEFAULT 1,
  `reason` VARCHAR(255) DEFAULT NULL,
  `issued_at` DATETIME(6) NOT NULL,
  `expires_at` DATETIME(6) NOT NULL,
  `revoked_at` DATETIME(6) DEFAULT NULL,
  `revoked_by` VARCHAR(80) DEFAULT NULL,
  `last_seen_at` DATETIME(6) DEFAULT NULL,
  `request_count` INT NOT NULL DEFAULT 0,
  `ip_address` VARCHAR(45) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_impersonation_token` (`token_id`),
  KEY `idx_impersonation_tenant` (`tenant_id`, `issued_at`),
  KEY `idx_impersonation_active` (`revoked_at`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- A running conversation log against a shop: what the owner asked for, what was promised,
-- why a module was switched on. Distinct from the audit trail, which records what the system
-- did; this records what the humans said.
CREATE TABLE IF NOT EXISTS `shop_notes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(100) NOT NULL,
  `body` TEXT NOT NULL,
  `category` VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
  `pinned` TINYINT(1) NOT NULL DEFAULT 0,
  `author` VARCHAR(80) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_shop_notes_tenant` (`tenant_id`, `pinned`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
