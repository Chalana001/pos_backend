-- Seed the payment methods that already exist in the codebase as hardcoded
-- strings, so the new management module works immediately after deploy. The
-- seed is derived from the real methods in use, not invented:
--   CASH, CARD, BANK, CREDIT   -> sale tenders (PaymentMethodUtil)
--   STORE_CREDIT               -> order-return refund method (OrderReturnService)
--   CHEQUE                     -> purchase / supplier / customer payment method
--   LEGACY                     -> backfilled historical order_payments (V59)
-- Idempotent: WHERE NOT EXISTS per code means re-running never duplicates and
-- never overwrites an admin's edits to name, flags, icon or active status.
-- Defaults chosen to preserve current behaviour: only CASH counts toward
-- expected drawer cash; only CREDIT allows deferred payment; BANK requires a
-- reference (matches the old chk_order_payments_bank_reference rule).
INSERT INTO payment_methods
    (code, display_name, category, reference_policy, reference_label,
     include_in_expected_cash, allows_credit, icon_key, active, is_system,
     sort_order, created_at)
SELECT seed.code, seed.display_name, seed.category, seed.reference_policy,
       seed.reference_label, seed.include_cash, seed.allows_credit,
       seed.icon_key, b'1', b'1', seed.sort_order, NOW(6)
FROM (
    SELECT 'CASH' AS code, 'Cash' AS display_name, 'CASH' AS category,
           'NONE' AS reference_policy, NULL AS reference_label,
           b'1' AS include_cash, b'0' AS allows_credit, 'Banknote' AS icon_key, 1 AS sort_order
    UNION ALL
    SELECT 'CARD', 'Card Payment', 'CARD', 'OPTIONAL', 'Approval Code',
           b'0', b'0', 'CreditCard', 2
    UNION ALL
    SELECT 'BANK', 'Bank Transfer', 'BANK_TRANSFER', 'REQUIRED', 'Bank Reference',
           b'0', b'0', 'Landmark', 3
    UNION ALL
    SELECT 'CREDIT', 'Credit', 'CREDIT', 'NONE', NULL,
           b'0', b'1', 'CheckCircle2', 4
    UNION ALL
    SELECT 'STORE_CREDIT', 'Store Credit', 'CREDIT', 'NONE', NULL,
           b'0', b'0', 'Wallet', 5
    UNION ALL
    SELECT 'CHEQUE', 'Cheque', 'BANK_TRANSFER', 'OPTIONAL', 'Cheque Number',
           b'0', b'0', 'FileText', 6
    UNION ALL
    SELECT 'LEGACY', 'Legacy', 'OTHER', 'NONE', NULL,
           b'0', b'0', 'Wallet', 99
) seed
WHERE NOT EXISTS (SELECT 1 FROM payment_methods pm WHERE pm.code = seed.code);
