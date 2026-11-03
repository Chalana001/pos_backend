package com.chala.posapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Serializes changes to one branch/item stock ledger.
 *
 * <p>A dedicated row exists independently of stock_batches, so it also covers
 * the first sale for a branch/item and the first negative batch creation.
 */
@Repository
@RequiredArgsConstructor
public class StockLedgerLockRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private volatile Boolean h2;

    public void lock(Long branchId, Long itemId) {
        if (isH2()) {
            jdbcTemplate.update("""
                    MERGE INTO stock_ledger_locks (branch_id, item_id, created_at)
                    KEY (branch_id, item_id)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, branchId, itemId);
        } else {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO stock_ledger_locks (branch_id, item_id, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP(6))
                    """, branchId, itemId);
        }

        Long locked = jdbcTemplate.queryForObject("""
                SELECT id FROM stock_ledger_locks
                WHERE branch_id = ? AND item_id = ?
                FOR UPDATE
                """, Long.class, branchId, itemId);
        if (locked == null) {
            throw new IllegalStateException(
                    "Stock ledger lock row was not created for " + branchId + "/" + itemId);
        }
    }

    private boolean isH2() {
        Boolean cached = h2;
        if (cached != null) {
            return cached;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean detected = connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT)
                    .contains("h2");
            h2 = detected;
            return detected;
        } catch (SQLException | DataAccessException exception) {
            throw new IllegalStateException("Could not identify the database for stock ledger locking", exception);
        }
    }
}