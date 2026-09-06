-- V40: Rule-based customer segments, so a promotion can target "customers who spent over X"
-- instead of a hand-typed list of ids.
--
-- A CUSTOMER-scope promotion stored one promotion_targets row per customer. A campaign for
-- everyone who spent over Rs. 50,000 last quarter meant enumerating those ids by hand into a
-- table that then held thousands of rows and went stale the next day. Customers also had
-- nothing to segment on — no tier, no points, no lifetime value — so there was nothing to
-- enumerate them from except a report read by eye.
--
-- A segment is a handful of thresholds over completed orders, not a query language: total
-- spend, order count, average order value, bought recently, or not bought for a while. Those
-- five cover what a shop actually asks for, and each is a column an operator can see and
-- change. Membership is materialised into customer_segment_members and recomputed on demand
-- rather than evaluated at the till: a promotion must not put an aggregate over every order
-- in the checkout path.
--
-- promotion_targets.segment_id is the alternative to customer_id on the same row — one names
-- a person, the other names a rule. Existing rows keep customer_id and are untouched.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31-V39.

CREATE TABLE IF NOT EXISTS `customer_segments` (
  `id`                    bigint NOT NULL AUTO_INCREMENT,
  `name`                  varchar(120) NOT NULL,
  `description`           varchar(255) NULL,
  `min_total_spend`       DECIMAL(19,4) NULL,
  `min_order_count`       int NULL,
  `min_avg_order_value`   DECIMAL(19,4) NULL,
  `purchased_within_days` int NULL,
  `inactive_for_days`     int NULL,
  `active`                bit(1) NOT NULL DEFAULT b'1',
  `member_count`          int NOT NULL DEFAULT 0,
  `last_evaluated_at`     datetime(6) NULL,
  `created_at`            datetime(6) NOT NULL,
  `updated_at`            datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_customer_segment_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `customer_segment_members` (
  `id`          bigint NOT NULL AUTO_INCREMENT,
  `segment_id`  bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `added_at`    datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_segment_member` (`segment_id`, `customer_id`),
  KEY `idx_tenant_segment_member_customer` (`customer_id`),
  CONSTRAINT `fk_segment_member_segment` FOREIGN KEY (`segment_id`) REFERENCES `customer_segments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP PROCEDURE IF EXISTS _v40_add_col;
CREATE PROCEDURE _v40_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v40_add_col('promotion_targets', 'segment_id', 'BIGINT NULL');

DROP PROCEDURE IF EXISTS _v40_add_col;
