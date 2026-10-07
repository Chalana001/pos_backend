-- Initial sale payment ledger. One row per completed sale, including a pure
-- credit sale (amount 0, shortfall = grand total). Later settlements stay in
-- credit_payments; this table must never gain a second row for one order.
CREATE TABLE order_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    invoice_no VARCHAR(40) NOT NULL,
    branch_id BIGINT NOT NULL,
    cashier_user_id BIGINT NOT NULL,
    amount DOUBLE NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    tendered_amount DOUBLE NULL,
    change_amount DOUBLE NULL,
    shortfall_amount DOUBLE NULL,
    bank_reference VARCHAR(120) NULL,
    note VARCHAR(255) NULL,
    paid_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NULL,
    CONSTRAINT fk_order_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_order_payments_order UNIQUE (order_id),
    CONSTRAINT chk_order_payments_amount_nonnegative CHECK (amount >= 0),
    CONSTRAINT chk_order_payments_tendered_nonnegative CHECK (tendered_amount IS NULL OR tendered_amount >= 0),
    CONSTRAINT chk_order_payments_change_nonnegative CHECK (change_amount IS NULL OR change_amount >= 0),
    CONSTRAINT chk_order_payments_shortfall_nonnegative CHECK (shortfall_amount IS NULL OR shortfall_amount >= 0),
    CONSTRAINT chk_order_payments_method CHECK (payment_method IN ('CASH', 'CARD', 'BANK', 'CREDIT')),
    CONSTRAINT chk_order_payments_bank_reference CHECK (payment_method <> 'BANK' OR bank_reference IS NOT NULL),
    INDEX idx_order_payments_branch (branch_id),
    INDEX idx_order_payments_method (payment_method)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
