-- Reset expense records and expense-driven history views.
-- This clears all tenant expense rows, clears custom expense types,
-- and resets cached shift expense totals so the flow can start fresh.
-- Run against the `pos_db` database.

START TRANSACTION;

DELETE FROM expenses;

DELETE FROM expense_types;

UPDATE cash_shifts
SET total_expenses = 0;

ALTER TABLE expenses AUTO_INCREMENT = 1;
ALTER TABLE expense_types AUTO_INCREMENT = 1;

COMMIT;
