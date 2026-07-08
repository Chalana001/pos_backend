CREATE TABLE IF NOT EXISTS subscription_plans (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    billing_cycle ENUM('MONTHLY','YEARLY') NOT NULL,
    initial_price DOUBLE NOT NULL,
    renewal_price DOUBLE NOT NULL,
    max_branches INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_plans_name (name)
);

CREATE TABLE IF NOT EXISTS tenant_subscriptions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(255) NOT NULL,
    shop_name VARCHAR(120) NOT NULL,
    admin_username VARCHAR(80) NOT NULL,
    plan_id BIGINT NOT NULL,
    is_active BIT(1) NOT NULL,
    blocked BIT(1) NOT NULL,
    extra_branches INT NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    notes VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_subscriptions_tenant_id (tenant_id),
    KEY idx_tenant_subscriptions_plan_id (plan_id),
    CONSTRAINT fk_tenant_subscriptions_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans (id)
);

CREATE TABLE IF NOT EXISTS billing_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80) NOT NULL,
    action_type ENUM('ONBOARDING','RENEWAL','PLAN_CHANGE','EXTRA_BRANCHES') NOT NULL,
    amount DOUBLE NOT NULL,
    shop_name VARCHAR(120) NOT NULL,
    reference_note VARCHAR(255) NULL,
    performed_by VARCHAR(80) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_billing_records_tenant_id (tenant_id),
    KEY idx_billing_records_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(255) NOT NULL,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('SUPER_ADMIN','ADMIN','MANAGER','CASHIER') NOT NULL,
    enabled BIT(1) NOT NULL,
    branch_id BIGINT NULL,
    offline_pin_hash VARCHAR(255) NULL,
    created_at DATETIME(6) NULL,
    offline_pin_updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username_tenant (username, tenant_id)
);

CREATE TABLE IF NOT EXISTS tenant_databases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100) NOT NULL,
    db_name VARCHAR(120) NOT NULL,
    status ENUM('PENDING','MIGRATED','FAILED') NOT NULL,
    created_at DATETIME(6) NULL,
    migrated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_databases_tenant_id (tenant_id)
);
