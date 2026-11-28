package com.progameflixx.cafectrl.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Plain-SQL access to {@code idempotency_keys}. Plain JDBC (not JPA) on purpose: a duplicate-key error on the
 * claim insert must not mark the surrounding transaction rollback-only. Times are UTC.
 */
@Component
public class IdempotencyStore {

    public static final Duration TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Stored(String action, String requestHash, Integer status, String body, LocalDateTime expiresAt) {
        boolean completed() { return status != null; }
    }

    /**
     * Claims the key inside the caller's transaction. Returns false if the key already exists. With InnoDB, a
     * concurrent claim of the same key waits until the first transaction commits (then fails) or rolls back
     * (then succeeds), so exactly one request ever runs the action.
     */
    public boolean claim(String scope, String key, String action, String requestHash) {
        LocalDateTime now = now();
        try {
            jdbc.update("INSERT INTO idempotency_keys (scope, idem_key, action, request_hash, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", scope, key, action, requestHash, now, now.plus(TTL));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Stored> find(String scope, String key) {
        List<Stored> rows = jdbc.query(
                "SELECT action, request_hash, response_status, response_body, expires_at FROM idempotency_keys WHERE scope = ? AND idem_key = ?",
                (rs, i) -> new Stored(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class)),
                scope, key);
        return rows.stream().findFirst();
    }

    public void complete(String scope, String key, int status, String body) {
        jdbc.update("UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE scope = ? AND idem_key = ?",
                status, body, scope, key);
    }

    /** Removes expired keys (daily cleanup). Touches only idempotency_keys. */
    public int deleteExpired(int batchSize) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM idempotency_keys WHERE expires_at < ? ORDER BY id LIMIT " + batchSize,
                Long.class, now());
        if (ids.isEmpty()) return 0;
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        return jdbc.update("DELETE FROM idempotency_keys WHERE id IN (" + in + ")");
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
