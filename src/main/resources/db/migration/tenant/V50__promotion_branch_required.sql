-- V50: a promotion belongs to exactly one branch.
--
-- branch_id has been nullable since the table was created, and null meant "every branch".
-- That stopped being a sensible default once a campaign became something priced against the
-- stock of the branch selling it: an all-branches campaign is judged on the worst batch
-- anywhere, offers items a branch may not carry, and gives a margin guard nothing definite to
-- measure. The application now refuses to create or update one without a branch, and this
-- makes the database agree rather than leaving the rule in one place only.
--
-- Any row still carrying null is adopted by the shop's first branch — rewriting a campaign is
-- wrong, but so is leaving a row the application can no longer express. The whole thing is
-- skipped if a shop somehow has such rows and no branches to move them to, so that a migration
-- can never be the reason a tenant fails to start; the application-level rule still holds there.

DROP PROCEDURE IF EXISTS _v50_require_promotion_branch;
CREATE PROCEDURE _v50_require_promotion_branch()
BEGIN
    DECLARE fallback_branch BIGINT DEFAULT NULL;
    DECLARE orphans INT DEFAULT 0;

    SELECT MIN(`id`) INTO fallback_branch FROM `branches`;
    SELECT COUNT(*) INTO orphans FROM `promotions` WHERE `branch_id` IS NULL;

    IF orphans > 0 AND fallback_branch IS NOT NULL THEN
        UPDATE `promotions` SET `branch_id` = fallback_branch WHERE `branch_id` IS NULL;
        SET orphans = 0;
    END IF;

    IF orphans = 0 AND EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'promotions'
          AND COLUMN_NAME = 'branch_id'
          AND IS_NULLABLE = 'YES'
    ) THEN
        ALTER TABLE `promotions` MODIFY COLUMN `branch_id` BIGINT NOT NULL;
    END IF;
END;

CALL _v50_require_promotion_branch();

DROP PROCEDURE IF EXISTS _v50_require_promotion_branch;
