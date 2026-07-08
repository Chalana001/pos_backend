ALTER TABLE `stock_processing_output_links`
    ADD COLUMN `default_selling_price` DECIMAL(10,2) NULL AFTER `default_quantity`;
