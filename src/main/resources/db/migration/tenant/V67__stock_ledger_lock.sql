-- V67: durable branch/item stock-ledger lock anchors.
--
-- POS checkout takes a write lock on one row per (branch_id, item_id) before
-- reading availability or creating an override batch. A dedicated row exists
-- even when stock_batches has no row yet, which closes the concurrent
-- first-time-override race.

CREATE TABLE IF NOT EXISTS `stock_ledger_locks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_ledger_lock_scope` (`branch_id`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;