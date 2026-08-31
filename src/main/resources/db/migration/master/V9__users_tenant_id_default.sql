-- The control-plane users table demands tenant_id, but tenancy in this app is
-- catalog-per-database: no entity maps a tenant_id column, so JPA can never
-- supply one. On any FRESH master database the super-admin bootstrap insert
-- died on this column - long-standing installs never noticed because their
-- row predates the constraint. CI's disposable containers hit it on every
-- boot. A default keeps the NOT NULL and the (username, tenant_id) unique
-- key intact while letting the bootstrap insert work.
ALTER TABLE users MODIFY COLUMN tenant_id VARCHAR(255) NOT NULL DEFAULT 'MASTER';
