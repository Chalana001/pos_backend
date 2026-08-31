-- ---------- V7__purchase_returns.sql ----------
-- Purchase Return / Debit Note feature
-- Creates purchase_returns and purchase_return_items tables

-- ---------- purchase_returns ----------

CREATE TABLE IF NOT EXISTS `purchase_returns` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `debit_note_no`         varchar(60)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `purchase_id`           bigint         NOT NULL,
  `purchase_invoice_no`   varchar(100)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `supplier_id`           bigint         NOT NULL,
  `grn_id`                bigint         NOT NULL,
  `branch_id`             bigint         NOT NULL,
  `processed_by_user_id`  bigint         NOT NULL,
  `status`                varchar(20)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'COMPLETED',
  `total_return_amount`   decimal(12,2)  NOT NULL,
  `reason`                varchar(500)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `note`                  varchar(500)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `created_at`            datetime(6)    NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_debit_note_no` (`debit_note_no`),
  KEY `idx_pr_purchase_id`  (`purchase_id`),
  KEY `idx_pr_supplier_id`  (`supplier_id`),
  KEY `idx_pr_grn_id`       (`grn_id`),
  KEY `idx_pr_branch_id`    (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- purchase_return_items ----------

CREATE TABLE IF NOT EXISTS `purchase_return_items` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `purchase_return_id`    bigint         NOT NULL,
  `grn_item_id`           bigint         NOT NULL,
  `item_id`               bigint         NOT NULL,
  `item_name`             varchar(160)   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `barcode`               varchar(80)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `return_qty`            int            NOT NULL,
  `cost_price`            decimal(12,2)  NOT NULL,
  `return_line_amount`    decimal(12,2)  NOT NULL,
  `stock_deducted`        tinyint(1)     NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_pri_return_id`   (`purchase_return_id`),
  KEY `idx_pri_grn_item_id` (`grn_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
