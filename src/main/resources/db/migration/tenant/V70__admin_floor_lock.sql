-- Durable singleton serialization row for legacy and tenant ADMIN-floor mutations.
-- This is intentionally separate from tenant-rbac ownership and readiness tables.
CREATE TABLE IF NOT EXISTS `admin_floor_lock` (
  `id` TINYINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `admin_floor_lock` (`id`)
SELECT 1
WHERE NOT EXISTS (SELECT 1 FROM `admin_floor_lock` WHERE `id` = 1);
