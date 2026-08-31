-- ---------- V15__barcode_label_settings.sql ----------
-- Branch-wise customizable barcode label (sticker) settings.

CREATE TABLE `barcode_label_settings` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `branch_id`                BIGINT       NOT NULL,
    `label_width_mm`           INT          NOT NULL DEFAULT 40,
    `label_height_mm`          INT          NOT NULL DEFAULT 25,
    `padding_top_mm`           INT          NOT NULL DEFAULT 1,
    `show_shop_name`           TINYINT(1)   NOT NULL DEFAULT 1,
    `shop_name_text`           VARCHAR(60)  NULL,
    `shop_name_font_size`      INT          NOT NULL DEFAULT 7,
    `shop_name_bold`           TINYINT(1)   NOT NULL DEFAULT 1,
    `show_item_name`           TINYINT(1)   NOT NULL DEFAULT 1,
    `item_name_source`         VARCHAR(20)  NOT NULL DEFAULT 'PRIMARY',
    `item_name_max_chars`      INT          NOT NULL DEFAULT 22,
    `item_name_font_size`      INT          NOT NULL DEFAULT 8,
    `barcode_format`           VARCHAR(20)  NOT NULL DEFAULT 'CODE128',
    `barcode_width`            DECIMAL(3,1) NOT NULL DEFAULT 1.5,
    `barcode_height`           INT          NOT NULL DEFAULT 35,
    `show_barcode_value`       TINYINT(1)   NOT NULL DEFAULT 1,
    `barcode_value_font_size`  INT          NOT NULL DEFAULT 12,
    `show_price`               TINYINT(1)   NOT NULL DEFAULT 1,
    `price_prefix`             VARCHAR(10)  NOT NULL DEFAULT 'Rs.',
    `price_font_size`          INT          NOT NULL DEFAULT 10,
    `price_bold`               TINYINT(1)   NOT NULL DEFAULT 1,
    `show_footer_text`         TINYINT(1)   NOT NULL DEFAULT 0,
    `footer_text`              VARCHAR(80)  NULL,
    `footer_font_size`         INT          NOT NULL DEFAULT 6,
    `created_at`               DATETIME     NOT NULL,
    `updated_at`               DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_barcode_label_branch` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
