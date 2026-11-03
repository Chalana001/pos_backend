-- V68: purchase-created negative-batch offsets.
--
-- When a purchase line offsets legacy negative stock (instead of adding the
-- receipt on top), the negative batch quantities change with nothing linking
-- them to the purchase that changed them. stock_batch_reconciliation_offsets is
-- migration history (V66) and knows nothing about purchases, so cancellation
-- had no way to know what to restore, or that anything needed restoring.
--
-- One row per negative batch a purchase line changed:
--   quantity_offset            signed movement applied to the batch
--   batch_quantity_after_offset  the batch quantity immediately after that
--                                movement; cancellation refuses while the
--                                batch no longer holds this value, because
--                                that means the stock was sold, returned or
--                                adjusted after the offset
--   reversed_at                set when the purchase is cancelled or
--                              superseded and the offset was undone
--
-- The rows are kept (marked, not deleted) so a cancelled purchase's stock
-- history stays readable.

CREATE TABLE IF NOT EXISTS `purchase_stock_offsets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `purchase_id` bigint NOT NULL,
  `grn_id` bigint NULL,
  `grn_item_id` bigint NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `negative_batch_id` bigint NOT NULL,
  `quantity_offset` bigint NOT NULL,
  `batch_quantity_after_offset` bigint NOT NULL,
  `reversed_at` datetime(6) NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_purchase_stock_offsets_purchase` (`purchase_id`),
  KEY `idx_purchase_stock_offsets_batch` (`negative_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
