-- Normalize existing stock processing history after the cancel-flow rollout.
-- This marks all current records as COMPLETED and clears cancel metadata.
-- Run against the `pos_db` database once.

START TRANSACTION;

UPDATE stock_processings
SET status = 'COMPLETED',
    cancel_reason = NULL,
    canceled_by_user_id = NULL,
    canceled_at = NULL;

COMMIT;
