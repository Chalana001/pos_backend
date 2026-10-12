-- Seed only the four checkout tenders the shop admin manages by default:
-- CASH, CARD, BANK, CREDIT. Other codes found in historical rows
-- (STORE_CREDIT refunds, CHEQUE purchases, LEGACY backfills) are NOT seeded
-- as methods: they stay readable on old transactions, rendered by the
-- frontend's unknown-method fallback, and admins add anything else they want
-- (QR, wallets) from Settings. Idempotent: WHERE NOT EXISTS per code, and a
-- method an admin has since configured under one of the legacy codes is
-- never overwritten by reseeding.
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
) seed
WHERE NOT EXISTS (SELECT 1 FROM payment_methods pm WHERE pm.code = seed.code);

-- V63.1 (same migration, later step): remove the extra codes this seed
-- originally created before the four-only contract. Only rows that are still
-- pristine system seeds (is_system = 1, never edited) are deleted so an admin
-- who deliberately configured one of these codes keeps their configuration.
DELETE pm FROM payment_methods pm
WHERE pm.code IN ('STORE_CREDIT', 'CHEQUE', 'LEGACY')
  AND pm.is_system = b'1';
