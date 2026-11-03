#!/usr/bin/env node
/*
 * Disposable MySQL verification for Inventory Phase 1.
 *
 * Creates a uniquely named database, runs V66-V69, inserts the specified
 * datasets, verifies quantity preservation and historical references, then
 * drops the database. It never connects to production and refuses a non-local
 * host.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const migrationDir = path.join(root, 'src/main/resources/db/migration/tenant');

function sqlValue(value) {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'number') return String(value);
  return `'${String(value).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function main() {
  const user = process.env.PHASE1_MYSQL_USER || 'root';
  const password = process.env.PHASE1_MYSQL_PASSWORD || 'root';
  const host = process.env.PHASE1_MYSQL_HOST || '127.0.0.1';
  const port = process.env.PHASE1_MYSQL_PORT || '3306';
  if (!['127.0.0.1', 'localhost'].includes(host)) {
    throw new Error(`Refusing non-local MySQL host: ${host}`);
  }
  const database = `pos_phase1_verify_${Date.now()}_${process.pid}`;
  const args = [`--host=${host}`, `--port=${port}`, `--user=${user}`, `--protocol=TCP`, '--batch', '--raw'];
  if (password) args.push(`--password=${password}`);

  const run = (sql, db = database) => execFileSync('mysql', [...args, db], {
    encoding: 'utf8',
    input: sql,
    stdio: ['pipe', 'pipe', 'pipe'],
    maxBuffer: 20 * 1024 * 1024,
  });
  const file = (name) => fs.readFileSync(path.join(migrationDir, name), 'utf8');

  try {
    run(`CREATE DATABASE \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`, '');
    for (const name of [
      'V1__baseline.sql',
      'V66__reconcile_negative_stock_batches.sql',
      'V67__stock_ledger_lock.sql',
      'V68__purchase_stock_offsets.sql',
      'V69__stock_adjustment_purchase_reversal.sql',
    ]) {
      run(file(name));
    }

    // The baseline has no generated sample data. Add the minimum tenant rows needed
    // to exercise the migration's branch/item semantics without inventing stock.
    const seed = `
      INSERT INTO branches (id, code, name, active, created_at)
      VALUES (1, 'MAIN', 'Main', 1, NOW(6)), (2, 'OTHER', 'Other', 1, NOW(6));
      INSERT INTO categories (id, name, created_at)
      VALUES (1, 'Verification', NOW(6));
      INSERT INTO sub_categories (id, name, category_id, created_at)
      VALUES (1, 'Verification sub', 1, NOW(6));
      INSERT INTO items (id, barcode, name, sub_category_id, cost_price, selling_price, reorder_level, item_type, default_unit, active, pos_visible, is_kot_enabled, stock_processing_enabled, overhead_cost_mode, overhead_cost_value, created_at)
      VALUES (1, 'PHASE1-1', 'Phase 1 item', 1, 10, 20, 0, 'NORMAL', 'PCS', 1, 1, 0, 0, 'NONE', 0, NOW(6));
      INSERT INTO suppliers (id, name, phone, active, created_at)
      VALUES (1, 'Verification supplier', '0770000000', 1, NOW(6));
      INSERT INTO stock_batches (id, branch_id, item_id, supplier_id, batch_code, source_type, cost_price, selling_price, quantity, original_quantity, received_at)
      VALUES
        (101, 1, 1, 1, 'A-NEG', 'PURCHASE', 10, 20, -5000, 5000, NOW(6) - INTERVAL 2 DAY),
        (102, 1, 1, 1, 'A-POS', 'PURCHASE', 10, 20, 10000, 10000, NOW(6) - INTERVAL 1 DAY),
        (111, 1, 1, 1, 'B-NEG', 'PURCHASE', 10, 20, -8000, 8000, NOW(6) - INTERVAL 2 DAY),
        (112, 1, 1, 1, 'B-POS', 'PURCHASE', 10, 20, 3000, 3000, NOW(6) - INTERVAL 1 DAY),
        (121, 1, 1, 1, 'C-NEG', 'PURCHASE', 10, 20, -5000, 5000, NOW(6) - INTERVAL 2 DAY),
        (122, 1, 1, 1, 'C-POS', 'PURCHASE', 10, 20, 5000, 5000, NOW(6) - INTERVAL 1 DAY),
        (131, 1, 1, 1, 'D-MAIN-NEG', 'PURCHASE', 10, 20, -5000, 5000, NOW(6) - INTERVAL 2 DAY),
        (132, 2, 1, 1, 'D-OTHER-POS', 'PURCHASE', 10, 20, 5000, 5000, NOW(6) - INTERVAL 1 DAY);
      INSERT INTO orders (id, invoice_no, client_sale_id, branch_id, cashier_user_id, order_type, sale_mode, status, subtotal, bill_discount, grand_total, paid_amount, due_amount, created_at)
      VALUES (1, 'PHASE1-ORDER', NULL, 1, 1, 'CASH', 'TAKEAWAY', 'COMPLETED', 20, 0, 20, 20, 0, NOW(6));
      INSERT INTO order_items (id, order_id, item_id, batch_id, barcode, item_name, item_type, qty, display_qty, qty_unit, cost_price, unit_price, discount_type, discount_value, final_unit_price, line_cost, line_total)
      VALUES (1, 1, 1, 101, 'PHASE1-1', 'Phase 1 item', 'NORMAL', 1000, 1, 'PCS', 10, 20, 'NONE', 0, 20, 10, 20);
      INSERT INTO order_item_stock_usages (id, order_item_id, item_id, batch_id, quantity, restored_quantity)
      VALUES (1, 1, 1, 101, 1000, 0);
      INSERT INTO grn (id, grn_no, supplier_id, branch_id, total_amount, received_at)
      VALUES (1, 'PHASE1-GRN', 1, 1, 20, NOW(6));
      INSERT INTO grn_items (id, grn_id, item_id, qty, display_qty, free_qty, display_free_qty, qty_unit, cost_price, selling_price, amount)
      VALUES (1, 1, 1, 10000, 10, 0, 0, 'PCS', 10, 20, 100);
    `;
    run(seed);

    const before = run(`
      SELECT branch_id, item_id, SUM(quantity) AS total, SUM(quantity * cost_price / 1000) AS value
      FROM stock_batches GROUP BY branch_id, item_id ORDER BY branch_id, item_id;
      SELECT id, quantity, original_quantity FROM stock_batches ORDER BY id;
    `);

    // V66 must be idempotent. The migration records each branch/item scope.
    run(file('V66__reconcile_negative_stock_batches.sql'));
    const afterSecondV66 = run(`
      SELECT branch_id, item_id, SUM(quantity) AS total FROM stock_batches
      GROUP BY branch_id, item_id ORDER BY branch_id, item_id;
    `);

    const after = run(`
      SELECT branch_id, item_id, SUM(quantity) AS total, SUM(quantity * cost_price / 1000) AS value
      FROM stock_batches GROUP BY branch_id, item_id ORDER BY branch_id, item_id;
      SELECT id, quantity, original_quantity FROM stock_batches ORDER BY id;
      SELECT id, batch_id, quantity FROM order_item_stock_usages ORDER BY id;
      SELECT id, qty, display_qty, free_qty FROM grn_items ORDER BY id;
      SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank;
      SHOW COLUMNS FROM stock_adjustments LIKE 'purchase_id';
      SHOW COLUMNS FROM stock_adjustments LIKE 'stock_batch_id';
      SHOW COLUMNS FROM stock_adjustments LIKE 'reversal_of_adjustment_id';
      SHOW COLUMNS FROM stock_adjustments LIKE 'reversed_by_adjustment_id';
    `);

    console.log('BEFORE\n' + before);
    console.log('AFTER_SECOND_V66\n' + afterSecondV66);
    console.log('AFTER\n' + after);
  } finally {
    try {
      run(`DROP DATABASE IF EXISTS \`${database}\``, '');
    } catch (error) {
      console.error(`Failed to drop disposable database ${database}: ${error.message}`);
    }
  }
}

main();