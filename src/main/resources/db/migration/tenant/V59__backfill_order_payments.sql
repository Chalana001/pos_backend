-- V59: Complete the initial-sale payment ledger for historical orders.
--
-- V58 created order_payments and new sales write their initial payment row
-- atomically with the order. Orders completed before V58 have no row, so
-- reporting and the order API lose the initial payment details for them.
--
-- The backfill never invents payment facts. Tender and change were never
-- carried, and before the dedicated sale totals existed the mutable
-- paid_amount/due_amount fields were updated by later credit settlements.
-- When sale_paid_amount is NULL there is no immutable initial-payment fact,
-- so the row records amount 0 with the full bill as shortfall instead of
-- turning settlement money into a fabricated initial payment.
--
-- legacy_backfilled separates these derived rows from live writes. It also lets
-- the method check admit CHEQUE and LEGACY only for historical rows: live sales
-- remain restricted to CASH, CARD, BANK and CREDIT, while a cheque or a null
-- legacy method is preserved rather than rewritten into a false current method.
--
-- credit_payments gets the same bank_reference field used by order_payments so
-- later credit settlements can persist the reference they already validate.
--
-- MySQL 8.0 has no ADD COLUMN ... IF NOT EXISTS, so this reuses the same
-- information_schema-guarded procedure pattern as V10/V27/V28/V31/V32.

DROP PROCEDURE IF EXISTS _v59_add_col;
CREATE PROCEDURE _v59_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

CALL _v59_add_col('order_payments', 'legacy_backfilled', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL _v59_add_col('credit_payments', 'bank_reference', 'VARCHAR(120) NULL');

DROP PROCEDURE IF EXISTS _v59_add_col;

-- V58 restricted live methods before legacy_backfilled existed. Rebuild the
-- check so CHEQUE and LEGACY are valid only on backfilled rows.
SET @sql = (
    SELECT CONCAT('ALTER TABLE `', TABLE_NAME, '` DROP CHECK `', CONSTRAINT_NAME, '`')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'order_payments'
      AND CONSTRAINT_NAME = 'chk_order_payments_method'
      AND CONSTRAINT_TYPE = 'CHECK'
    LIMIT 1
);
PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;

ALTER TABLE order_payments
    ADD CONSTRAINT chk_order_payments_method CHECK (
        (
            legacy_backfilled = 0
            AND payment_method IN ('CASH', 'CARD', 'BANK', 'CREDIT')
        )
        OR (
            legacy_backfilled = 1
            AND payment_method IN ('CASH', 'CARD', 'BANK', 'CREDIT', 'CHEQUE', 'LEGACY')
        )
    );

-- The legacy orders do not carry the bank reference. Keep the live rule - a BANK
-- row needs one - while allowing the historical row to stay truthful as NULL.
SET @sql = (
    SELECT CONCAT('ALTER TABLE `', TABLE_NAME, '` DROP CHECK `', CONSTRAINT_NAME, '`')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'order_payments'
      AND CONSTRAINT_NAME = 'chk_order_payments_bank_reference'
      AND CONSTRAINT_TYPE = 'CHECK'
    LIMIT 1
);
PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;

ALTER TABLE order_payments
    ADD CONSTRAINT chk_order_payments_bank_reference CHECK (
        payment_method <> 'BANK'
        OR bank_reference IS NOT NULL
        OR legacy_backfilled = 1
    );

INSERT INTO order_payments (
    order_id, invoice_no, branch_id, cashier_user_id, amount,
    payment_method, tendered_amount, change_amount, shortfall_amount,
    bank_reference, note, paid_at, created_at, legacy_backfilled
)
SELECT
    o.id,
    COALESCE(o.sale_paid_amount, 0),
    CASE
        WHEN o.payment_method IN ('CASH', 'CARD', 'BANK', 'CREDIT', 'CHEQUE') THEN o.payment_method
        ELSE 'LEGACY'
    END,
    NULL,
    NULL,
    COALESCE(
        o.sale_due_amount,
        GREATEST(o.grand_total - COALESCE(o.sale_paid_amount, 0), 0)
    ),
    NULL,
    'Legacy order payment backfill',
    COALESCE(o.offline_sold_at, o.created_at, NOW(6)),
    NOW(6),
    1
FROM orders o
WHERE o.status = 'COMPLETED'
  AND NOT EXISTS (
      SELECT 1
      FROM order_payments op
      WHERE op.order_id = o.id
  );
