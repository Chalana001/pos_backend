-- Flyway baseline: tenant-scoped schema for pos_<slug> databases.
-- AUTO-GENERATED from pos_db by scripts/build_v2_baseline.mjs.
-- The tenant_id column is intentionally absent in every table; routing is
-- per-catalog, so single-tenant DBs have no need for the column.

SET FOREIGN_KEY_CHECKS = 0;


-- ---------- app_configurations ----------

CREATE TABLE IF NOT EXISTS `app_configurations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `dine_in_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `recipe_items_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `services_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `table_management_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime(6) NOT NULL,
  `weight_items_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `category_mode` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MAIN_AND_SUB',
  `stock_override_mode` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MANAGER_OVERRIDE',
  `admin_stock_override_allowed` tinyint(1) NOT NULL DEFAULT '1',
  `admin_warranty_allowed` tinyint(1) NOT NULL DEFAULT '1',
  `cashier_stock_override_allowed` tinyint(1) NOT NULL DEFAULT '0',
  `cashier_warranty_allowed` tinyint(1) NOT NULL DEFAULT '0',
  `kot_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `manager_stock_override_allowed` tinyint(1) NOT NULL DEFAULT '1',
  `manager_warranty_allowed` tinyint(1) NOT NULL DEFAULT '1',
  `warranty_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `branch_id` bigint DEFAULT NULL,
  `print_receipt_after_checkout` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKjs0c27htx3gj0apju9v493lwl` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- branch_sequences ----------

CREATE TABLE IF NOT EXISTS `branch_sequences` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `next_invoice_number` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- branch_service_items ----------

CREATE TABLE IF NOT EXISTS `branch_service_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9c2gowi7mf88ll9olu28rd7tp` (`branch_id`,`item_id`),
  KEY `idx_tenant_branch_service_branch` (`branch_id`),
  KEY `idx_tenant_branch_service_item` (`item_id`),
  KEY `FKoa36uqdu0w6pehe81c0uykpdq` (`branch_id`),
  KEY `FK1m66jt5vgcs7wbi5lf7v1oxog` (`item_id`),
  CONSTRAINT `FK1m66jt5vgcs7wbi5lf7v1oxog` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`),
  CONSTRAINT `FKoa36uqdu0w6pehe81c0uykpdq` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- branches ----------

CREATE TABLE IF NOT EXISTS `branches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `code` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `phone` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `logo` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKf5h7dngjs0brektfdwyrx5n4j` (`code`),
  UNIQUE KEY `UKhwlukmr8ahb87btkdlyu4il9s` (`name`),
  KEY `idx_tenant_branch_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- cash_drops ----------

CREATE TABLE IF NOT EXISTS `cash_drops` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` double NOT NULL,
  `branch_id` bigint NOT NULL,
  `cashier_user_id` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `shift_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- cash_shifts ----------

CREATE TABLE IF NOT EXISTS `cash_shifts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `cash_difference` double DEFAULT NULL,
  `cash_sales` double NOT NULL,
  `cashier_user_id` bigint NOT NULL,
  `close_note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `counted_cash` double DEFAULT NULL,
  `expected_cash` double DEFAULT NULL,
  `open_note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `opened_at` datetime(6) DEFAULT NULL,
  `opening_cash` double NOT NULL,
  `status` enum('CLOSED','OPEN') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `total_cash_drops` double NOT NULL,
  `total_expenses` double NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- categories ----------

CREATE TABLE IF NOT EXISTS `categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK2eekc1gx9rwg2h69xct9gp3il` (`name`),
  KEY `idx_tenant_category_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- credit_payments ----------

CREATE TABLE IF NOT EXISTS `credit_payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` double NOT NULL,
  `branch_id` bigint NOT NULL,
  `cashier_user_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `payment_method` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- customer_notes ----------

CREATE TABLE IF NOT EXISTS `customer_notes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `created_by` bigint DEFAULT NULL,
  `customer_id` bigint NOT NULL,
  `note` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `pinned` bit(1) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- customers ----------

CREATE TABLE IF NOT EXISTS `customers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `credit_limit` double DEFAULT NULL,
  `due_amount` double NOT NULL,
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKhcpdq9tenaji3n5vs4oxublce` (`phone`),
  KEY `idx_tenant_customer_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- dining_tables ----------

CREATE TABLE IF NOT EXISTS `dining_tables` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `status` enum('AVAILABLE','OCCUPIED') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `table_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK7alxjeje5deomwrgkg0erlv9r` (`branch_id`,`table_name`),
  KEY `idx_tenant_branch_table` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- expense_types ----------

CREATE TABLE IF NOT EXISTS `expense_types` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `count_in_profit_report` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKf4b6qyvpk6jglcw9tlmsmy1r3` (`name`),
  KEY `idx_tenant_expense_type_name` (`name`),
  KEY `idx_tenant_expense_type_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- expenses ----------

CREATE TABLE IF NOT EXISTS `expenses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` double NOT NULL,
  `branch_id` bigint NOT NULL,
  `cashier_user_id` bigint NOT NULL,
  `category` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `shift_id` bigint DEFAULT NULL,
  `count_in_profit_report` bit(1) NOT NULL,
  `expense_type_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_branch_shift_exp` (`branch_id`,`shift_id`),
  KEY `idx_tenant_created_at_exp` (`created_at`),
  KEY `idx_tenant_expense_type_exp` (`expense_type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- grn ----------

CREATE TABLE IF NOT EXISTS `grn` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_by_user_id` bigint DEFAULT NULL,
  `grn_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `paid_amount` decimal(12,2) DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `total_amount` decimal(12,2) DEFAULT NULL,
  `branch_id` bigint NOT NULL,
  `purchase_id` bigint DEFAULT NULL,
  `supplier_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKrgsybdm7wgm2npv9703cgprn` (`grn_no`),
  KEY `idx_tenant_grn_no` (`grn_no`),
  KEY `FK5yqe0im0kankyj7bn723kmn4s` (`branch_id`),
  KEY `FKkiaawgqsx5hb6bcc9sex9yo4k` (`purchase_id`),
  KEY `FKfqyvatkv6pbr66an6hn2vb69u` (`supplier_id`),
  CONSTRAINT `FK5yqe0im0kankyj7bn723kmn4s` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`),
  CONSTRAINT `FKfqyvatkv6pbr66an6hn2vb69u` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`),
  CONSTRAINT `FKkiaawgqsx5hb6bcc9sex9yo4k` FOREIGN KEY (`purchase_id`) REFERENCES `purchase` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- grn_items ----------

CREATE TABLE IF NOT EXISTS `grn_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(38,2) DEFAULT NULL,
  `cost_price` decimal(38,2) DEFAULT NULL,
  `qty` int DEFAULT NULL,
  `selling_price` decimal(38,2) DEFAULT NULL,
  `grn_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `display_qty` decimal(12,3) NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKchwuo5ufpm5jws3yuhkv4l4y8` (`grn_id`),
  KEY `FKk5eht4geuw7rxjbb5dg6iny8e` (`item_id`),
  CONSTRAINT `FKchwuo5ufpm5jws3yuhkv4l4y8` FOREIGN KEY (`grn_id`) REFERENCES `grn` (`id`),
  CONSTRAINT `FKk5eht4geuw7rxjbb5dg6iny8e` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- items ----------

CREATE TABLE IF NOT EXISTS `items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `barcode` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `cost_price` decimal(10,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `reorder_level` int NOT NULL,
  `selling_price` decimal(10,2) NOT NULL,
  `sub_category_id` bigint DEFAULT NULL,
  `default_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `item_type` enum('NORMAL','RECIPE','SERVICE','VOLUME','WEIGHT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `is_kot_enabled` bit(1) NOT NULL,
  `pos_visible` tinyint(1) NOT NULL DEFAULT '1',
  `stock_processing_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `overhead_cost_mode` enum('FIXED','NONE','PERCENT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `overhead_cost_value` decimal(10,2) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9lhkvcyn28ghh2oji40p25aaj` (`barcode`),
  KEY `idx_tenant_barcode` (`barcode`),
  KEY `FKsjc20ih7rqir43b75v72jwxgr` (`sub_category_id`),
  CONSTRAINT `FKsjc20ih7rqir43b75v72jwxgr` FOREIGN KEY (`sub_category_id`) REFERENCES `sub_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- order_item_stock_usages ----------

CREATE TABLE IF NOT EXISTS `order_item_stock_usages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_order_item_usage` (`order_item_id`),
  KEY `idx_tenant_batch_usage` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- order_items ----------

CREATE TABLE IF NOT EXISTS `order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `barcode` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `batch_id` bigint DEFAULT NULL,
  `cost_price` double NOT NULL,
  `discount_type` enum('FIXED','NONE','PERCENT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `discount_value` double NOT NULL,
  `final_unit_price` double NOT NULL,
  `item_id` bigint NOT NULL,
  `item_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `line_total` double NOT NULL,
  `order_id` bigint NOT NULL,
  `qty` int NOT NULL,
  `unit_price` double NOT NULL,
  `display_qty` decimal(12,3) NOT NULL,
  `line_cost` double NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `item_type` enum('NORMAL','RECIPE','SERVICE','VOLUME','WEIGHT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `warranty_label` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `warranty_period_unit` enum('DAYS','MONTHS','YEARS') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `warranty_period_value` int DEFAULT NULL,
  `promotion_discount_amount` double NOT NULL,
  `promotion_id` bigint DEFAULT NULL,
  `promotion_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_order_items_lookup` (`order_id`),
  KEY `idx_tenant_item_sales_lookup` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- orders ----------

CREATE TABLE IF NOT EXISTS `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bill_discount` double NOT NULL,
  `branch_id` bigint NOT NULL,
  `cancel_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `cashier_user_id` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `customer_id` bigint DEFAULT NULL,
  `due_amount` double NOT NULL,
  `grand_total` double NOT NULL,
  `invoice_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `order_type` enum('CASH','CREDIT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `paid_amount` double NOT NULL,
  `status` enum('CANCELED','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `sub_total` double NOT NULL,
  `receipt_branch_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `receipt_branch_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `receipt_branch_phone` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `receipt_branch_logo` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci,
  `sale_mode` enum('DINE_IN','TAKEAWAY') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `table_id` bigint DEFAULT NULL,
  `table_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `client_sale_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `imported_at` datetime(6) DEFAULT NULL,
  `offline_imported` bit(1) NOT NULL,
  `offline_sold_at` datetime(6) DEFAULT NULL,
  `payment_method` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sale_due_amount` double DEFAULT NULL,
  `sale_paid_amount` double DEFAULT NULL,
  `bill_promotion_discount_amount` double NOT NULL,
  `bill_promotion_id` bigint DEFAULT NULL,
  `bill_promotion_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `promotion_discount_total` double NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKg1qnhtcr5l0weliisxlravgb4` (`invoice_no`),
  UNIQUE KEY `UKp10nua4jy7lbv8shf5o8fayg7` (`client_sale_id`),
  KEY `idx_tenant_invoice` (`invoice_no`),
  KEY `idx_tenant_branch_order` (`branch_id`),
  KEY `idx_tenant_client_sale` (`client_sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- pending_order_items ----------

CREATE TABLE IF NOT EXISTS `pending_order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint DEFAULT NULL,
  `discount_type` enum('FIXED','NONE','PERCENT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `discount_value` double NOT NULL,
  `display_qty` decimal(12,3) NOT NULL,
  `item_id` bigint NOT NULL,
  `pending_order_id` bigint NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `unit_price` double NOT NULL,
  `warranty_label` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `warranty_period_unit` enum('DAYS','MONTHS','YEARS') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `warranty_period_value` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_pending_order_items` (`pending_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- pending_orders ----------

CREATE TABLE IF NOT EXISTS `pending_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bill_discount` double NOT NULL,
  `branch_id` bigint NOT NULL,
  `cashier_user_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `customer_id` bigint DEFAULT NULL,
  `grand_total` double NOT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sub_total` double NOT NULL,
  `table_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8aqgn6cqr0vskoaxofkxqd4vp` (`table_id`),
  KEY `idx_tenant_branch_pending_order` (`branch_id`),
  KEY `idx_tenant_table_pending_order` (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- promotion_targets ----------

CREATE TABLE IF NOT EXISTS `promotion_targets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint DEFAULT NULL,
  `customer_id` bigint DEFAULT NULL,
  `item_id` bigint DEFAULT NULL,
  `sub_category_id` bigint DEFAULT NULL,
  `promotion_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_promotion_target_promotion` (`promotion_id`),
  KEY `idx_tenant_promotion_target_item` (`item_id`),
  KEY `idx_tenant_promotion_target_category` (`category_id`),
  KEY `idx_tenant_promotion_target_subcategory` (`sub_category_id`),
  KEY `idx_tenant_promotion_target_customer` (`customer_id`),
  KEY `FKadouvnh81yd8a64bk7wn435je` (`promotion_id`),
  CONSTRAINT `FKadouvnh81yd8a64bk7wn435je` FOREIGN KEY (`promotion_id`) REFERENCES `promotions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- promotions ----------

CREATE TABLE IF NOT EXISTS `promotions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `branch_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `discount_type` enum('FIXED','NONE','PERCENT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `discount_value` double NOT NULL,
  `end_at` datetime(6) NOT NULL,
  `max_discount_amount` double NOT NULL,
  `min_bill_amount` double NOT NULL,
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `priority` int NOT NULL,
  `scope` enum('BILL','CATEGORY','CUSTOMER','ITEM') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `start_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_promotion_active_dates` (`active`,`start_at`,`end_at`),
  KEY `idx_tenant_promotion_branch` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- purchase ----------

CREATE TABLE IF NOT EXISTS `purchase` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `grand_total` decimal(12,2) DEFAULT NULL,
  `invoice_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `supplier_id` bigint NOT NULL,
  `cancel_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `status` enum('CANCELED','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'COMPLETED',
  `discount_amount` decimal(12,2) DEFAULT NULL,
  `due_amount` decimal(12,2) DEFAULT NULL,
  `paid_amount` decimal(12,2) DEFAULT NULL,
  `payment_method` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `cash_shift_id` bigint DEFAULT NULL,
  `cash_source` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'BRANCH_CASH',
  `cash_source_amount` decimal(12,2) NOT NULL DEFAULT '0.00',
  `cash_source_branch_id` bigint DEFAULT NULL,
  `cashier_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKm4hnft7g6615ig06i4msboyey` (`supplier_id`,`invoice_no`),
  KEY `FK7wa1ltbppmk5drn8uqq1nxmut` (`supplier_id`),
  CONSTRAINT `FK7wa1ltbppmk5drn8uqq1nxmut` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- receipt_template_settings ----------

CREATE TABLE IF NOT EXISTS `receipt_template_settings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `credits_line1` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `credits_line2` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `logo_width_percent` int NOT NULL,
  `paper_width_mm` int NOT NULL,
  `show_address` bit(1) NOT NULL,
  `show_balance` bit(1) NOT NULL,
  `show_branch_name` bit(1) NOT NULL,
  `show_cashier` bit(1) NOT NULL,
  `show_credits` bit(1) NOT NULL,
  `show_customer` bit(1) NOT NULL,
  `show_date_time` bit(1) NOT NULL,
  `show_discount` bit(1) NOT NULL,
  `show_invoice_number` bit(1) NOT NULL,
  `show_item_table` bit(1) NOT NULL,
  `show_logo` bit(1) NOT NULL,
  `show_net_total` bit(1) NOT NULL,
  `show_paid` bit(1) NOT NULL,
  `show_phone` bit(1) NOT NULL,
  `show_store_name` bit(1) NOT NULL,
  `show_subtotal` bit(1) NOT NULL,
  `show_thanks_message` bit(1) NOT NULL,
  `template_type` enum('A4','KOT','THERMAL') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `thanks_message` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `branch_id` bigint NOT NULL,
  `invoice_logo_width_percent` int NOT NULL DEFAULT '78',
  `show_address_label` tinyint(1) NOT NULL DEFAULT '1',
  `show_phone_label` tinyint(1) NOT NULL DEFAULT '1',
  `show_due_amount` tinyint(1) NOT NULL DEFAULT '1',
  `show_warranty` tinyint(1) NOT NULL DEFAULT '1',
  `logo_top_spacing` int NOT NULL DEFAULT '4',
  `receipt_font_family` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'COURIER_NEW',
  `direct_print_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `printer_copies` int NOT NULL DEFAULT '1',
  `printer_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKpo8yi1hssg91yussqnlepukp7` (`branch_id`,`template_type`),
  KEY `idx_tenant_branch_receipt_template` (`branch_id`,`template_type`),
  KEY `FKsiwv8u9bjkox4iy0j964fxa5d` (`branch_id`),
  CONSTRAINT `FKsiwv8u9bjkox4iy0j964fxa5d` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- recipe_ingredients ----------

CREATE TABLE IF NOT EXISTS `recipe_ingredients` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ingredient_id` bigint NOT NULL,
  `parent_item_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKqw8ho7v09p3ej3kqa39g9reyp` (`parent_item_id`,`ingredient_id`),
  KEY `idx_tenant_recipe_parent` (`parent_item_id`),
  KEY `idx_tenant_recipe_ingredient` (`ingredient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock ----------

CREATE TABLE IF NOT EXISTS `stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKl9hv0q75t7cr7j8osylbp9c52` (`branch_id`,`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_adjustments ----------

CREATE TABLE IF NOT EXISTS `stock_adjustments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `item_id` bigint NOT NULL,
  `qty_change` int NOT NULL,
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `type` enum('DAMAGED','EXPIRED','FOUND','LOST','MANUAL','NEW_STOCK') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `display_qty_change` decimal(12,3) NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_branch_item_adj` (`branch_id`,`item_id`),
  KEY `idx_tenant_created_at_adj` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_batches ----------

CREATE TABLE IF NOT EXISTS `stock_batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `cost_price` decimal(12,2) NOT NULL,
  `expire_date` datetime(6) DEFAULT NULL,
  `original_quantity` int NOT NULL,
  `quantity` int NOT NULL,
  `received_at` datetime(6) NOT NULL,
  `selling_price` decimal(12,2) NOT NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `supplier_id` bigint DEFAULT NULL,
  `origin_batch_id` bigint DEFAULT NULL,
  `source_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PURCHASE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKcxkcd1aou4wn1kjjdrjbjg07m` (`batch_code`),
  KEY `idx_tenant_batch_item_branch` (`item_id`,`branch_id`),
  KEY `idx_tenant_batch_expiry` (`expire_date`),
  KEY `FK47pm54ds6tns67lo3eo9t2esx` (`branch_id`),
  KEY `FKfc9f6sn1nxgaak0evbx4xu5i6` (`item_id`),
  KEY `FKldhty33ndu0onnt0qxhqg1dhu` (`supplier_id`),
  KEY `idx_tenant_batch_origin_branch` (`origin_batch_id`,`branch_id`),
  CONSTRAINT `FK47pm54ds6tns67lo3eo9t2esx` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`),
  CONSTRAINT `FKfc9f6sn1nxgaak0evbx4xu5i6` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`),
  CONSTRAINT `FKldhty33ndu0onnt0qxhqg1dhu` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_override_audits ----------

CREATE TABLE IF NOT EXISTS `stock_override_audits` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `available_quantity` int NOT NULL,
  `batch_id` bigint DEFAULT NULL,
  `branch_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `order_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `override_mode` enum('ALWAYS_ALLOW','BLOCK','MANAGER_OVERRIDE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `override_user_id` bigint NOT NULL,
  `override_username` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `required_quantity` int NOT NULL,
  `sale_item_id` bigint NOT NULL,
  `sale_item_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `shortage_quantity` int NOT NULL,
  `stock_item_id` bigint NOT NULL,
  `stock_item_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_stock_override_order` (`order_id`),
  KEY `idx_tenant_stock_override_stock_item` (`stock_item_id`),
  KEY `idx_tenant_stock_override_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_processing_output_links ----------

CREATE TABLE IF NOT EXISTS `stock_processing_output_links` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `output_item_id` bigint NOT NULL,
  `source_item_id` bigint NOT NULL,
  `is_waste` bit(1) NOT NULL,
  `default_quantity` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKe9d7ryp2tculie6o7eefi3r1k` (`source_item_id`,`output_item_id`),
  KEY `idx_tenant_processing_link_source` (`source_item_id`),
  KEY `idx_tenant_processing_link_output` (`output_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_processing_outputs ----------

CREATE TABLE IF NOT EXISTS `stock_processing_outputs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `allocated_cost` decimal(12,2) NOT NULL,
  `created_batch_id` bigint DEFAULT NULL,
  `display_qty` decimal(12,3) NOT NULL,
  `processing_id` bigint NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `quantity` int NOT NULL,
  `is_waste` bit(1) NOT NULL,
  `output_item_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_stock_processing_outputs` (`processing_id`),
  KEY `idx_tenant_stock_processing_output_item` (`output_item_id`),
  KEY `FKobh7c2dm344j25vvoa8kqpeel` (`output_item_id`),
  CONSTRAINT `FKobh7c2dm344j25vvoa8kqpeel` FOREIGN KEY (`output_item_id`) REFERENCES `items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_processings ----------

CREATE TABLE IF NOT EXISTS `stock_processings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `processed_at` datetime(6) NOT NULL,
  `processed_by_user_id` bigint NOT NULL,
  `source_batch_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `source_batch_id` bigint NOT NULL,
  `source_cost` decimal(12,2) NOT NULL,
  `source_display_qty` decimal(12,3) NOT NULL,
  `source_qty` int NOT NULL,
  `source_qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `branch_id` bigint NOT NULL,
  `source_item_id` bigint NOT NULL,
  `cancel_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `canceled_by_user_id` bigint DEFAULT NULL,
  `processing_status` enum('CANCELED','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_stock_processing_branch` (`branch_id`,`processed_at`),
  KEY `idx_tenant_stock_processing_source` (`source_item_id`,`processed_at`),
  KEY `FK948qmsy1ugjd21s7m3wv43tlt` (`branch_id`),
  KEY `FKovbwc2v4om2s4k3bxyv8fh2ph` (`source_item_id`),
  CONSTRAINT `FK948qmsy1ugjd21s7m3wv43tlt` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`),
  CONSTRAINT `FKovbwc2v4om2s4k3bxyv8fh2ph` FOREIGN KEY (`source_item_id`) REFERENCES `items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_transfer_items ----------

CREATE TABLE IF NOT EXISTS `stock_transfer_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `barcode` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `batch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `item_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `qty` int NOT NULL,
  `transfer_id` bigint NOT NULL,
  `display_qty` decimal(12,3) NOT NULL,
  `qty_unit` enum('G','KG','L','ML','PCS','SERVICE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKofvmu93p0d6jqi71cevis6jer` (`transfer_id`,`batch_id`),
  KEY `idx_tenant_transfer_items_fast` (`transfer_id`),
  KEY `FKertcckucd49nmw8ley2j5pxmh` (`transfer_id`),
  CONSTRAINT `FKertcckucd49nmw8ley2j5pxmh` FOREIGN KEY (`transfer_id`) REFERENCES `stock_transfers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- stock_transfers ----------

CREATE TABLE IF NOT EXISTS `stock_transfers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cancel_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `from_branch_id` bigint NOT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `received_by_user_id` bigint DEFAULT NULL,
  `requested_at` datetime(6) NOT NULL,
  `requested_by_user_id` bigint NOT NULL,
  `status` enum('CANCELED','COMPLETED','IN_TRANSIT') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `to_branch_id` bigint NOT NULL,
  `transfer_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK3k7l6n1erpf8n3p5btkn4af8t` (`transfer_no`),
  KEY `idx_tenant_transfer_no` (`transfer_no`),
  KEY `idx_tenant_transfer_branches` (`from_branch_id`,`to_branch_id`),
  KEY `idx_tenant_transfer_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- sub_categories ----------

CREATE TABLE IF NOT EXISTS `sub_categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `category_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKrt7camfocqrtrd6jt0auhghx1` (`name`),
  KEY `idx_tenant_subcategory_name` (`name`),
  KEY `FKjwy7imy3rf6r99x48ydq45otw` (`category_id`),
  CONSTRAINT `FKjwy7imy3rf6r99x48ydq45otw` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- supplier_bank_details ----------

CREATE TABLE IF NOT EXISTS `supplier_bank_details` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `account_number` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `bank_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `branch_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `supplier_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKp3n95omkov1xfs3lr9cv1w1u8` (`supplier_id`),
  CONSTRAINT `FKecytapadrpa0248c51qojb4s2` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- supplier_contacts ----------

CREATE TABLE IF NOT EXISTS `supplier_contacts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contact_number` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `supplier_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK1g9gcs0mm7oqmvrwab9jpyg3g` (`supplier_id`),
  CONSTRAINT `FK1g9gcs0mm7oqmvrwab9jpyg3g` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- supplier_items ----------

CREATE TABLE IF NOT EXISTS `supplier_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_buying_price` double DEFAULT NULL,
  `primary_supplier` bit(1) DEFAULT NULL,
  `supplier_item_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `item_id` bigint DEFAULT NULL,
  `supplier_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK7wvppsin657d30and7ul6vt9h` (`item_id`),
  KEY `FKm2t6dgtc9r1a39fop5375dtma` (`supplier_id`),
  CONSTRAINT `FK7wvppsin657d30and7ul6vt9h` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`),
  CONSTRAINT `FKm2t6dgtc9r1a39fop5375dtma` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- supplier_payments ----------

CREATE TABLE IF NOT EXISTS `supplier_payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(12,2) NOT NULL,
  `created_by_user_id` bigint DEFAULT NULL,
  `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `paid_at` datetime(6) NOT NULL,
  `payment_method` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `purchase_id` bigint DEFAULT NULL,
  `supplier_id` bigint NOT NULL,
  `cash_shift_id` bigint DEFAULT NULL,
  `cash_source` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'BRANCH_CASH',
  `cash_source_branch_id` bigint DEFAULT NULL,
  `cashier_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_supplier_payment_supplier` (`supplier_id`),
  KEY `idx_tenant_supplier_payment_paid_at` (`paid_at`),
  KEY `FKn98iqevw1v4tstm5qpih9aao3` (`purchase_id`),
  KEY `FKdwv3fhnvnbuvd6h2ri8iuiw2q` (`supplier_id`),
  CONSTRAINT `FKdwv3fhnvnbuvd6h2ri8iuiw2q` FOREIGN KEY (`supplier_id`) REFERENCES `suppliers` (`id`),
  CONSTRAINT `FKn98iqevw1v4tstm5qpih9aao3` FOREIGN KEY (`purchase_id`) REFERENCES `purchase` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- suppliers ----------

CREATE TABLE IF NOT EXISTS `suppliers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) DEFAULT NULL,
  `address` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `due_amount` decimal(12,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmgiq09o918av6p3eu3xlpe9b4` (`phone`),
  UNIQUE KEY `UKmt13g9xuvhy1pyke6aueyu8ql` (`email`),
  KEY `idx_tenant_supplier_phone` (`phone`),
  KEY `idx_tenant_supplier_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- users ----------

CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `branch_id` bigint DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `enabled` bit(1) NOT NULL,
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `role` enum('ADMIN','CASHIER','MANAGER','SUPER_ADMIN') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `username` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `offline_pin_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `offline_pin_updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6us5hyddnpphouyqyog4xcjs8` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- warranties ----------

CREATE TABLE IF NOT EXISTS `warranties` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `barcode` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `branch_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `customer_id` bigint DEFAULT NULL,
  `customer_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `end_date` date NOT NULL,
  `invoice_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `item_id` bigint NOT NULL,
  `item_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `order_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `period_unit` enum('DAYS','MONTHS','YEARS') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `period_value` int NOT NULL,
  `start_date` date NOT NULL,
  `status` enum('ACTIVE','CLAIMED','EXPIRED','VOID') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `warranty_label` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `warranty_no` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKjbv9n41sdhv833kqa19g5ifu4` (`warranty_no`),
  KEY `idx_tenant_warranty_lookup` (`warranty_no`),
  KEY `idx_tenant_warranty_order` (`order_id`),
  KEY `idx_tenant_warranty_branch` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- warranty_claims ----------

CREATE TABLE IF NOT EXISTS `warranty_claims` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action_type` enum('REPAIR','REPLACE') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `branch_id` bigint NOT NULL,
  `claim_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `issue_description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `received_at` datetime(6) NOT NULL,
  `resolution_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` enum('CANCELED','COMPLETED','IN_PROGRESS','OPEN','REJECTED') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `warranty_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKbr3ruesggm0sns9ma07yyh3fj` (`claim_no`),
  KEY `idx_tenant_warranty_claim_warranty` (`warranty_id`),
  KEY `idx_tenant_warranty_claim_branch` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- warranty_templates ----------

CREATE TABLE IF NOT EXISTS `warranty_templates` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `label` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `period_unit` enum('DAYS','MONTHS','YEARS') CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `period_value` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKryiud4n029w6imxsnwta2rq70` (`label`),
  KEY `idx_tenant_warranty_templates_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- app_migrations: legacy bookkeeping table (kept for parity).
CREATE TABLE IF NOT EXISTS `app_migrations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `migration_key` varchar(255) NOT NULL,
  `applied_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_migration_key` (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

SET FOREIGN_KEY_CHECKS = 1;
