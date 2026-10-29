-- Database-backed payment method configuration. One row per payment method per
-- tenant database (this migration runs inside each tenant schema). Behavior is
-- driven by flags, not by the display name: include_in_expected_cash marks a
-- method cash-like (drawer/expected-cash), reference_policy drives the optional
-- or required reference number, allows_credit marks deferred payment. The
-- stable `code` is the immutable identifier; `display_name` may be renamed
-- without touching historical orders/order_payments.
CREATE TABLE payment_methods (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    category VARCHAR(30) NOT NULL,
    reference_policy VARCHAR(10) NOT NULL DEFAULT 'NONE',
    reference_label VARCHAR(60) NULL,
    include_in_expected_cash BIT NOT NULL DEFAULT b'0',
    allows_credit BIT NOT NULL DEFAULT b'0',
    icon_key VARCHAR(60) NULL,
    active BIT NOT NULL DEFAULT b'1',
    is_system BIT NOT NULL DEFAULT b'0',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    CONSTRAINT uk_payment_methods_code UNIQUE (code),
    CONSTRAINT chk_payment_methods_category CHECK (category IN ('CASH','CARD','BANK_TRANSFER','MOBILE_DIGITAL','CREDIT','OTHER')),
    CONSTRAINT chk_payment_methods_ref_policy CHECK (reference_policy IN ('NONE','OPTIONAL','REQUIRED')),
    INDEX idx_payment_methods_active (active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The order_payments method CHECK only allows the four original sale tenders,
-- which would reject any newly configured method. Replace it with a wide
-- format check (non-blank, length-capped) so DB-backed methods are accepted;
-- the authoritative validation is the payment_methods table via the service.
DROP PROCEDURE IF EXISTS _v62_drop_pm_check;
CREATE PROCEDURE _v62_drop_pm_check()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND TABLE_NAME = 'order_payments'
          AND CONSTRAINT_NAME = 'chk_order_payments_method'
    ) THEN
        ALTER TABLE order_payments DROP CHECK chk_order_payments_method;
    END IF;
END;
CALL _v62_drop_pm_check();
DROP PROCEDURE IF EXISTS _v62_drop_pm_check;

ALTER TABLE order_payments
    ADD CONSTRAINT chk_order_payments_method_format
    CHECK (CHAR_LENGTH(TRIM(payment_method)) > 0 AND CHAR_LENGTH(payment_method) <= 20);

-- The BANK-only reference rule is superseded by per-method reference_policy;
-- required references are now enforced in the service for ANY method whose
-- policy is REQUIRED. Keep a soft integrity rule: a reference may be stored
-- for any method, so drop the BANK-specific constraint.
DROP PROCEDURE IF EXISTS _v62_drop_ref_check;
CREATE PROCEDURE _v62_drop_ref_check()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND TABLE_NAME = 'order_payments'
          AND CONSTRAINT_NAME = 'chk_order_payments_bank_reference'
    ) THEN
        ALTER TABLE order_payments DROP CHECK chk_order_payments_bank_reference;
    END IF;
END;
CALL _v62_drop_ref_check();
DROP PROCEDURE IF EXISTS _v62_drop_ref_check;
