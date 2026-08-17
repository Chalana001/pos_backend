-- V27: Make "one open shift per cashier per branch" a database guarantee.
--
-- Why: ShiftService.openShift() did a findByBranchIdAndCashierUserIdAndStatus()
-- check followed by a save(). Two requests fired milliseconds apart (a cashier
-- double-clicking "Open Shift", because on Windows they expect double-click to
-- act) both ran the SELECT before either ran the INSERT, so both passed the
-- check and two OPEN shifts were created. Application-level checks can never
-- close that window; only a unique index can.
--
-- MySQL has no partial/filtered indexes, so we use a STORED generated column
-- that is NULL for CLOSED shifts (NULLs are ignored by unique indexes) and
-- "<branch_id>:<cashier_user_id>" for OPEN ones. The unique index on that
-- column therefore constrains open shifts only.
--
-- MySQL 8.0 does NOT support ADD COLUMN/ADD INDEX ... IF NOT EXISTS, so the
-- same information_schema-guarded procedure pattern as V10 is used here.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Close the duplicate OPEN shifts that the race already created.
--    The oldest row in each (branch, cashier) group is the real shift — its
--    opened_at covers the sales window, and orders are matched to a shift by
--    time range, not by a shift_id foreign key. Every later row in the group is
--    an accidental double-submit and gets closed with a traceable note.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE `cash_shifts` cs
JOIN (
    SELECT MIN(`id`) AS keep_id, `branch_id`, `cashier_user_id`
    FROM `cash_shifts`
    WHERE `status` = 'OPEN'
    GROUP BY `branch_id`, `cashier_user_id`
    HAVING COUNT(*) > 1
) dup
  ON cs.`branch_id`       = dup.`branch_id`
 AND cs.`cashier_user_id` = dup.`cashier_user_id`
 AND cs.`id`             <> dup.`keep_id`
SET cs.`status`          = 'CLOSED',
    cs.`closed_at`       = NOW(),
    cs.`counted_cash`    = IFNULL(cs.`counted_cash`, cs.`opening_cash`),
    cs.`expected_cash`   = IFNULL(cs.`expected_cash`, cs.`opening_cash`),
    cs.`cash_difference` = IFNULL(cs.`cash_difference`, 0),
    cs.`close_note`      = CONCAT(
        'Auto-closed by migration V27: duplicate open shift created by a ',
        'double-submitted open request. Kept shift #', dup.`keep_id`, '.'
    )
WHERE cs.`status` = 'OPEN';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Add the guard column + unique index.
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS _v27_add_col;
CREATE PROCEDURE _v27_add_col(tbl VARCHAR(64), col VARCHAR(64), col_def TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

DROP PROCEDURE IF EXISTS _v27_add_unique_idx;
CREATE PROCEDURE _v27_add_unique_idx(tbl VARCHAR(64), idx VARCHAR(64), cols TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD UNIQUE INDEX `', idx, '` (', cols, ')');
        PREPARE _s FROM @sql; EXECUTE _s; DEALLOCATE PREPARE _s;
    END IF;
END;

-- NULL whenever the shift is closed → closed shifts are exempt from the index.
CALL _v27_add_col(
    'cash_shifts',
    'open_shift_lock',
    'VARCHAR(64) GENERATED ALWAYS AS (
         CASE WHEN `status` = ''OPEN''
              THEN CONCAT(`branch_id`, '':'', `cashier_user_id`)
              ELSE NULL
         END
     ) STORED'
);

CALL _v27_add_unique_idx('cash_shifts', 'uk_cash_shifts_open_lock', '`open_shift_lock`');

DROP PROCEDURE IF EXISTS _v27_add_col;
DROP PROCEDURE IF EXISTS _v27_add_unique_idx;
