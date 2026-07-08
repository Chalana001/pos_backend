-- ---------- V6__order_returns.sql ----------
-- Partial Return / Refund feature
-- Creates order_returns and order_return_items tables

-- ---------- order_returns ----------

CREATE TABLE IF NOT EXISTS `order_returns` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `return_no`             varchar(60)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `original_order_id`     bigint         NOT NULL,
  `original_invoice_no`   varchar(40)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `branch_id`             bigint         NOT NULL,
  `cashier_user_id`       bigint         NOT NULL,
  `customer_id`           bigint         DEFAULT NULL,
  `status`                varchar(20)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'COMPLETED',
  `refund_method`         varchar(30)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `total_refund_amount`   decimal(12,2)  NOT NULL,
  `reason`                varchar(500)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `cashier_note`          varchar(500)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `created_at`            datetime(6)    NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_return_no` (`return_no`),
  KEY `idx_return_original_order` (`original_order_id`),
  KEY `idx_return_branch`         (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- order_return_items ----------

CREATE TABLE IF NOT EXISTS `order_return_items` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `order_return_id`       bigint         NOT NULL,
  `order_item_id`         bigint         NOT NULL,
  `item_id`               bigint         NOT NULL,
  `item_name`             varchar(160)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `barcode`               varchar(80)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `return_qty`            int            NOT NULL,
  `unit_price`            decimal(12,2)  NOT NULL,
  `final_unit_price`      decimal(12,2)  NOT NULL,
  `refund_line_amount`    decimal(12,2)  NOT NULL,
  `stock_reversed`        tinyint(1)     NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_return_items_return_id`  (`order_return_id`),
  KEY `idx_return_items_order_item` (`order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
