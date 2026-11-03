-- V66: Normalize legacy stock-batch ledgers.
--
-- The migration reconciles each branch/item independently and never combines
-- quantities across branches. For a signed total:
--
--   negative total:
--     1. offset negatives from oldest to newest, preferring same-selling-price
--        positives, falling back to other positives oldest first;
--     2. leave the remaining negative total on one batch (the oldest negative
--        that still has a balance after offsets);
--     3. zero every other row.
--
--   positive total:
--     consume the oldest positive rows first, without price preference, and
--     zero all negative rows. Any positive remainder stays on its original row.
--
--   zero total:
--     zero every row.
--
-- Rows and IDs are never deleted: order_item_stock_usages and returns still
-- reference them. GRN lines, purchase totals, selling prices and historical
-- purchase lines are untouched.
--
-- Every quantity this migration changes is audited in
-- stock_batch_reconciliation_offsets: pairwise receipt-style offsets
-- (POSITIVE_TOTAL_OLDEST, SAME_PRICE, ANY_PRICE) as they are applied, and the
-- row-scoped changes of zero-total ledgers (ZERO_TOTAL_CLEAR) and
-- negative-total consolidation (NEGATIVE_TOTAL_CANONICAL for the row that
-- retains the liability, NEGATIVE_TOTAL_CANONICAL_ZERO for each row zeroed),
-- each with the quantity before and after the change.
--
-- Re-running is safe: a branch/item ledger already recorded in the run table is
-- skipped, and the unique scope key also prevents duplicate run rows. Every
-- DML change happens after the staging tables are created, inside one
-- transaction. Any per-ledger total mismatch or leftover second negative row
-- raises and rolls the whole migration back.

CREATE TABLE IF NOT EXISTS `stock_batch_reconciliation_runs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `migration_version` varchar(32) NOT NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `total_before` bigint NOT NULL,
  `total_after` bigint NOT NULL,
  `negative_before` bigint NOT NULL,
  `negative_after` bigint NOT NULL,
  `positive_before` bigint NOT NULL,
  `positive_after` bigint NOT NULL,
  `offsets_applied` int NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_v66_reconciliation_scope` (`migration_version`, `branch_id`, `item_id`),
  KEY `idx_v66_reconciliation_branch_item` (`branch_id`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- quantity_offset is the signed movement this record applied to the named row.
-- quantity_before/quantity_after are filled on the row-scoped records
-- (ZERO_TOTAL_CLEAR, NEGATIVE_TOTAL_CANONICAL_ZERO, NEGATIVE_TOTAL_CANONICAL)
-- and bracket the exact change; pairwise offset records describe two rows at
-- once and keep quantity_offset as their movement.
CREATE TABLE IF NOT EXISTS `stock_batch_reconciliation_offsets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `negative_batch_id` bigint NULL,
  `positive_batch_id` bigint NULL,
  `quantity_offset` bigint NOT NULL,
  `quantity_before` bigint NULL,
  `quantity_after` bigint NULL,
  `negative_selling_price` decimal(12,2) NULL,
  `positive_selling_price` decimal(12,2) NULL,
  `match_type` varchar(32) NOT NULL,
  `reason` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_v66_offsets_run` (`run_id`),
  KEY `idx_v66_offsets_negative_batch` (`negative_batch_id`),
  KEY `idx_v66_offsets_positive_batch` (`positive_batch_id`),
  CONSTRAINT `fk_v66_offsets_run`
    FOREIGN KEY (`run_id`) REFERENCES `stock_batch_reconciliation_runs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- MySQL cannot reopen a TEMPORARY table inside the self-join used by a pass.
-- These are disposable permanent staging tables, created before the transaction
-- so DDL can never implicitly commit the reconciliation halfway through.
DROP TABLE IF EXISTS `_v66_work_offsets`;
DROP TABLE IF EXISTS `_v66_work_batches`;
DROP TABLE IF EXISTS `_v66_totals`;

CREATE TABLE `_v66_work_batches` (
  `batch_id` bigint NOT NULL,
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `selling_price` decimal(12,2) NULL,
  `quantity` bigint NOT NULL,
  `original_quantity` bigint NOT NULL,
  `received_at` datetime(6) NOT NULL,
  PRIMARY KEY (`batch_id`),
  KEY `idx_v66_work_scope` (`branch_id`, `item_id`, `quantity`),
  KEY `idx_v66_work_positive` (`branch_id`, `item_id`, `selling_price`, `quantity`)
) ENGINE=InnoDB;

CREATE TABLE `_v66_work_offsets` (
  `pair_id` bigint NOT NULL AUTO_INCREMENT,
  `negative_batch_id` bigint NULL,
  `positive_batch_id` bigint NULL,
  `quantity_offset` bigint NOT NULL,
  `match_type` varchar(32) NOT NULL,
  PRIMARY KEY (`pair_id`),
  KEY `idx_v66_work_offsets_negative` (`negative_batch_id`),
  KEY `idx_v66_work_offsets_positive` (`positive_batch_id`)
) ENGINE=InnoDB;

CREATE TABLE `_v66_totals` (
  `branch_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `total_before` bigint NOT NULL,
  `total_after` bigint NOT NULL,
  PRIMARY KEY (`branch_id`, `item_id`)
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS _v66_reconcile_negative_stock;

CREATE PROCEDURE _v66_reconcile_negative_stock()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE cur_branch_id bigint;
    DECLARE cur_item_id bigint;
    DECLARE total_before bigint;
    DECLARE negative_before bigint;
    DECLARE positive_before bigint;
    DECLARE total_after bigint;
    DECLARE negative_after bigint;
    DECLARE positive_after bigint;
    DECLARE offset_count int DEFAULT 0;
    DECLARE canonical_negative_id bigint;
    DECLARE canonical_negative_balance bigint;
    DECLARE run_id bigint;
    DECLARE all_batches_zero int DEFAULT 0;
    DECLARE current_positive_id bigint;
    DECLARE offset_qty bigint;

    DECLARE scope_cursor CURSOR FOR
        SELECT eligible.branch_id, eligible.item_id
        FROM (
            SELECT branch_id, item_id
            FROM stock_batches
            GROUP BY branch_id, item_id
        ) eligible
        LEFT JOIN stock_batch_reconciliation_runs existing_run
          ON existing_run.migration_version = 'V66'
         AND existing_run.branch_id = eligible.branch_id
         AND existing_run.item_id = eligible.item_id
        WHERE existing_run.id IS NULL
        ORDER BY eligible.branch_id, eligible.item_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    OPEN scope_cursor;
    scope_loop: LOOP
        FETCH scope_cursor INTO cur_branch_id, cur_item_id;
        IF done = 1 THEN
            LEAVE scope_loop;
        END IF;

        DELETE FROM _v66_work_batches;
        DELETE FROM _v66_work_offsets;
        SET offset_count = 0;
        SET canonical_negative_id = NULL;
        SET canonical_negative_balance = 0;

        INSERT INTO _v66_work_batches
            (batch_id, branch_id, item_id, selling_price, quantity,
             original_quantity, received_at)
        SELECT id, branch_id, item_id, selling_price, quantity,
               original_quantity, received_at
        FROM stock_batches
        WHERE branch_id = cur_branch_id
          AND item_id = cur_item_id
        ORDER BY received_at ASC, id ASC;

        SELECT COALESCE(SUM(quantity), 0),
               COALESCE(SUM(CASE WHEN quantity < 0 THEN -quantity ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN quantity > 0 THEN quantity ELSE 0 END), 0)
          INTO total_before, negative_before, positive_before
        FROM _v66_work_batches;

        -- The run row is inserted first because its unique scope key is the
        -- durable guard against applying the same offsets twice.
        INSERT INTO stock_batch_reconciliation_runs
            (migration_version, branch_id, item_id, total_before, total_after,
             negative_before, negative_after, positive_before, positive_after,
             offsets_applied)
        VALUES
            ('V66', cur_branch_id, cur_item_id, total_before, total_before,
             negative_before, negative_before, positive_before, positive_before,
             0);
        SET run_id = LAST_INSERT_ID();

        IF total_before > 0 THEN
            -- Positive total: offset every negative row using positives from
            -- oldest to newest, without any selling-price preference. Only the
            -- negative liability is consumed; the positive remainder stays on
            -- its original row (example: -5, +3, +4 -> 0, 0, +2).
            positive_offset: LOOP
                DELETE FROM _v66_work_offsets;
                INSERT INTO _v66_work_offsets
                    (negative_batch_id, positive_batch_id, quantity_offset, match_type)
                SELECT n.batch_id, p.batch_id, LEAST(-n.quantity, p.quantity),
                       'POSITIVE_TOTAL_OLDEST'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p
                  ON p.branch_id = n.branch_id
                 AND p.item_id = n.item_id
                 AND p.quantity > 0
                WHERE n.branch_id = cur_branch_id
                  AND n.item_id = cur_item_id
                  AND n.quantity < 0
                ORDER BY n.received_at ASC, n.batch_id ASC,
                         p.received_at ASC, p.batch_id ASC
                LIMIT 1;

                IF ROW_COUNT() = 0 THEN
                    LEAVE positive_offset;
                END IF;

                SELECT negative_batch_id, positive_batch_id, quantity_offset
                  INTO canonical_negative_id, current_positive_id, offset_qty
                FROM _v66_work_offsets
                LIMIT 1;

                UPDATE _v66_work_batches
                SET quantity = quantity + offset_qty
                WHERE batch_id = canonical_negative_id;
                UPDATE _v66_work_batches
                SET quantity = quantity - offset_qty
                WHERE batch_id = current_positive_id;

                INSERT INTO stock_batch_reconciliation_offsets
                    (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                     quantity_offset, negative_selling_price, positive_selling_price,
                     match_type, reason)
                SELECT run_id, cur_branch_id, cur_item_id,
                       n.batch_id, p.batch_id, offset_qty,
                       n.selling_price, p.selling_price, 'POSITIVE_TOTAL_OLDEST',
                       'V66 stock-batch reconciliation (positive-total oldest-first)'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p ON p.batch_id = current_positive_id
                WHERE n.batch_id = canonical_negative_id;
                SET offset_count = offset_count + 1;
            END LOOP;

            -- Positive-total offsets were written as they happened. Leave the
            -- staging table empty so nothing can replay them.
            DELETE FROM _v66_work_offsets;
        ELSEIF total_before < 0 THEN
            -- Negative total: offset oldest negative rows first. Same-price
            -- positives are preferred whenever one exists, then any remaining
            -- positive rows are used oldest first.
            -- Same-price matching is a full pass across all negative rows before
            -- any different-price stock is used. This prevents an earlier
            -- Sell 30 negative from consuming Sell 35 stock needed by a
            -- Sell 35 negative later in the ledger.
            negative_same_price: LOOP
                DELETE FROM _v66_work_offsets;
                INSERT INTO _v66_work_offsets
                    (negative_batch_id, positive_batch_id, quantity_offset, match_type)
                SELECT n.batch_id, p.batch_id, LEAST(-n.quantity, p.quantity),
                       'SAME_PRICE'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p
                  ON p.branch_id = n.branch_id
                 AND p.item_id = n.item_id
                 AND p.quantity > 0
                 AND p.selling_price IS NOT NULL
                 AND n.selling_price IS NOT NULL
                 AND p.selling_price = n.selling_price
                WHERE n.branch_id = cur_branch_id
                  AND n.item_id = cur_item_id
                  AND n.quantity < 0
                ORDER BY n.received_at ASC, n.batch_id ASC,
                         p.received_at ASC, p.batch_id ASC
                LIMIT 1;

                IF ROW_COUNT() = 0 THEN
                    LEAVE negative_same_price;
                END IF;

                SELECT negative_batch_id, positive_batch_id, quantity_offset
                  INTO canonical_negative_id, current_positive_id, offset_qty
                FROM _v66_work_offsets
                LIMIT 1;

                UPDATE _v66_work_batches
                SET quantity = quantity + offset_qty
                WHERE batch_id = canonical_negative_id;
                UPDATE _v66_work_batches
                SET quantity = quantity - offset_qty
                WHERE batch_id = current_positive_id;

                INSERT INTO stock_batch_reconciliation_offsets
                    (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                     quantity_offset, negative_selling_price, positive_selling_price,
                     match_type, reason)
                SELECT run_id, cur_branch_id, cur_item_id,
                       n.batch_id, p.batch_id, offset_qty,
                       n.selling_price, p.selling_price, 'SAME_PRICE',
                       'V66 stock-batch reconciliation (same-price)'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p ON p.batch_id = current_positive_id
                WHERE n.batch_id = canonical_negative_id;
                SET offset_count = offset_count + 1;
            END LOOP;

            negative_any_price: LOOP
                DELETE FROM _v66_work_offsets;
                INSERT INTO _v66_work_offsets
                    (negative_batch_id, positive_batch_id, quantity_offset, match_type)
                SELECT n.batch_id, p.batch_id, LEAST(-n.quantity, p.quantity),
                       'ANY_PRICE'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p
                  ON p.branch_id = n.branch_id
                 AND p.item_id = n.item_id
                 AND p.quantity > 0
                WHERE n.branch_id = cur_branch_id
                  AND n.item_id = cur_item_id
                  AND n.quantity < 0
                ORDER BY n.received_at ASC, n.batch_id ASC,
                         p.received_at ASC, p.batch_id ASC
                LIMIT 1;

                IF ROW_COUNT() = 0 THEN
                    LEAVE negative_any_price;
                END IF;

                SELECT negative_batch_id, positive_batch_id, quantity_offset
                  INTO canonical_negative_id, current_positive_id, offset_qty
                FROM _v66_work_offsets
                LIMIT 1;

                UPDATE _v66_work_batches
                SET quantity = quantity + offset_qty
                WHERE batch_id = canonical_negative_id;
                UPDATE _v66_work_batches
                SET quantity = quantity - offset_qty
                WHERE batch_id = current_positive_id;

                INSERT INTO stock_batch_reconciliation_offsets
                    (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                     quantity_offset, negative_selling_price, positive_selling_price,
                     match_type, reason)
                SELECT run_id, cur_branch_id, cur_item_id,
                       n.batch_id, p.batch_id, offset_qty,
                       n.selling_price, p.selling_price, 'ANY_PRICE',
                       'V66 stock-batch reconciliation (any-price)'
                FROM _v66_work_batches n
                JOIN _v66_work_batches p ON p.batch_id = current_positive_id
                WHERE n.batch_id = canonical_negative_id;
                SET offset_count = offset_count + 1;
            END LOOP;

            -- Keep the remaining negative total on exactly one row. After the
            -- offset loop, choose the oldest negative row that still has a
            -- balance; any other negative balance has been fully offset.
            DELETE FROM _v66_work_offsets;
            INSERT INTO _v66_work_offsets
                (negative_batch_id, positive_batch_id, quantity_offset, match_type)
            SELECT batch_id, NULL, -quantity, 'NEGATIVE_TOTAL_CANONICAL'
            FROM _v66_work_batches
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id
              AND quantity < 0
            ORDER BY received_at ASC, batch_id ASC
            LIMIT 1;

            SELECT negative_batch_id INTO canonical_negative_id
            FROM _v66_work_offsets
            LIMIT 1;
            SET canonical_negative_balance = -total_before;

            -- Consolidation moves the uncovered liability onto the canonical row
            -- and zeroes every other row that still carried a balance. These are
            -- row-scoped changes, so each one gets its own audit record with the
            -- quantity before and after the change, inserted while the work
            -- table still holds the before state.
            INSERT INTO stock_batch_reconciliation_offsets
                (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                 quantity_offset, quantity_before, quantity_after,
                 negative_selling_price, positive_selling_price,
                 match_type, reason)
            SELECT run_id, cur_branch_id, cur_item_id, batch_id, NULL,
                   -canonical_negative_balance - quantity, quantity,
                   -canonical_negative_balance, selling_price, NULL,
                   'NEGATIVE_TOTAL_CANONICAL',
                   'V66 stock-batch reconciliation (negative-total canonical row)'
            FROM _v66_work_batches
            WHERE batch_id = canonical_negative_id;

            INSERT INTO stock_batch_reconciliation_offsets
                (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                 quantity_offset, quantity_before, quantity_after,
                 negative_selling_price, positive_selling_price,
                 match_type, reason)
            SELECT run_id, cur_branch_id, cur_item_id, batch_id, NULL,
                   -quantity, quantity, 0, selling_price, NULL,
                   'NEGATIVE_TOTAL_CANONICAL_ZERO',
                   'V66 stock-batch reconciliation (negative-total row zeroed)'
            FROM _v66_work_batches
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id
              AND batch_id <> canonical_negative_id
              AND quantity <> 0;

            UPDATE _v66_work_batches
            SET quantity = CASE WHEN batch_id = canonical_negative_id
                                THEN -canonical_negative_balance
                                ELSE 0 END
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id;
            DELETE FROM _v66_work_offsets;
        ELSE
            -- Zero total: there is no stock left to assign to any row. Every row
            -- that actually loses a balance gets its own audit record, so the
            -- zeroing is as traceable as the pairwise offsets above.
            INSERT INTO stock_batch_reconciliation_offsets
                (run_id, branch_id, item_id, negative_batch_id, positive_batch_id,
                 quantity_offset, quantity_before, quantity_after,
                 negative_selling_price, positive_selling_price,
                 match_type, reason)
            SELECT run_id, cur_branch_id, cur_item_id,
                   CASE WHEN quantity < 0 THEN batch_id ELSE NULL END,
                   CASE WHEN quantity > 0 THEN batch_id ELSE NULL END,
                   -quantity, quantity, 0,
                   CASE WHEN quantity < 0 THEN selling_price ELSE NULL END,
                   CASE WHEN quantity > 0 THEN selling_price ELSE NULL END,
                   'ZERO_TOTAL_CLEAR',
                   'V66 stock-batch reconciliation (zero-total clear)'
            FROM _v66_work_batches
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id
              AND quantity <> 0;

            UPDATE _v66_work_batches
            SET quantity = 0
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id;
        END IF;

        SELECT COALESCE(SUM(quantity), 0),
               COALESCE(SUM(CASE WHEN quantity < 0 THEN -quantity ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN quantity > 0 THEN quantity ELSE 0 END), 0)
          INTO total_after, negative_after, positive_after
        FROM _v66_work_batches;

        IF total_before <> total_after THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'V66 aborted: a branch/item total changed during reconciliation';
        END IF;
        IF total_after < 0 THEN
            SELECT COUNT(*) INTO all_batches_zero
            FROM _v66_work_batches
            WHERE branch_id = cur_branch_id
              AND item_id = cur_item_id
              AND quantity < 0;
            IF all_batches_zero <> 1 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'V66 aborted: negative total was not consolidated to one batch';
            END IF;
        END IF;

        INSERT INTO _v66_totals (branch_id, item_id, total_before, total_after)
        VALUES (cur_branch_id, cur_item_id, total_before, total_after);

        UPDATE stock_batch_reconciliation_runs
        SET total_after = total_after,
            negative_after = negative_after,
            positive_after = positive_after,
            offsets_applied = offset_count
        WHERE id = run_id;

        UPDATE stock_batches sb
        JOIN _v66_work_batches wb ON wb.batch_id = sb.id
        SET sb.quantity = wb.quantity
        WHERE sb.branch_id = cur_branch_id
          AND sb.item_id = cur_item_id
          AND sb.quantity <> wb.quantity;
    END LOOP;
    CLOSE scope_cursor;

    COMMIT;

    SELECT COUNT(*) AS affected_branch_item_ledgers,
           COALESCE(SUM(offsets_applied), 0) AS offsets_applied,
           COALESCE(SUM(negative_after), 0) AS unresolved_negative_quantity
    FROM stock_batch_reconciliation_runs
    WHERE migration_version = 'V66';
END;

CALL _v66_reconcile_negative_stock();

DROP PROCEDURE IF EXISTS _v66_reconcile_negative_stock;
DROP TABLE IF EXISTS `_v66_work_offsets`;
DROP TABLE IF EXISTS `_v66_work_batches`;
DROP TABLE IF EXISTS `_v66_totals`;