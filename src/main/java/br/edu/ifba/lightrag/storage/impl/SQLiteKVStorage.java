package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.KVStorage;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-based implementation of KVStorage.
 * 
 * <p>Simple key-value storage using the kv_store table.
 * Supports pattern matching for key lookups using SQL LIKE.</p>
 */
public final class SQLiteKVStorage implements KVStorage {

    private static final Logger LOG = Logger.getLogger(SQLiteKVStorage.class);

    private final SQLiteConnectionManager connectionManager;

    /**
     * Creates a new SQLiteKVStorage.
     *
     * @param connectionManager the SQLite connection manager
     */
    public SQLiteKVStorage(SQLiteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            LOG.info("Initialized SQLiteKVStorage");
        });
    }

    @Override
    public CompletableFuture<String> get(@NotNull String key) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT value FROM kv_store WHERE key = ?";

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("value");
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get value for key: " + key, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> getBatch(@NotNull List<String> keys) {
        return CompletableFuture.supplyAsync(() -> {
            if (keys.isEmpty()) {
                return new HashMap<>();
            }

            StringBuilder sql = new StringBuilder("SELECT key, value FROM kv_store WHERE key IN (");
            sql.append("?,".repeat(keys.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Map<String, String> result = new HashMap<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < keys.size(); i++) {
                    stmt.setString(i + 1, keys.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("key"), rs.getString("value"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get batch values", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Void> set(@NotNull String key, @NotNull String value) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO kv_store (key, value, created_at, updated_at)
                VALUES (?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                stmt.setString(2, value);
                stmt.executeUpdate();
                LOG.debugf("Set key %s", key);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to set value for key: " + key, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setBatch(@NotNull Map<String, String> entries) {
        return CompletableFuture.runAsync(() -> {
            if (entries.isEmpty()) {
                return;
            }

            String sql = """
                INSERT INTO kv_store (key, value, created_at, updated_at)
                VALUES (?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (Map.Entry<String, String> entry : entries.entrySet()) {
                        stmt.setString(1, entry.getKey());
                        stmt.setString(2, entry.getValue());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
                conn.commit();
                LOG.debugf("Batch set %d entries", entries.size());
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to batch set values", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOG.warn("Failed to reset auto-commit", ex);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(@NotNull String key) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM kv_store WHERE key = ?";

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                int deleted = stmt.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete key: " + key, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteBatch(@NotNull List<String> keys) {
        return CompletableFuture.supplyAsync(() -> {
            if (keys.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder("DELETE FROM kv_store WHERE key IN (");
            sql.append("?,".repeat(keys.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < keys.size(); i++) {
                    stmt.setString(i + 1, keys.get(i));
                }
                return stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to batch delete keys", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(@NotNull String key) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM kv_store WHERE key = ?";

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check key existence: " + key, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> keys() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT key FROM kv_store";

            List<String> keys = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("key"));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get all keys", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return keys;
        });
    }

    @Override
    public CompletableFuture<List<String>> keys(@NotNull String pattern) {
        return CompletableFuture.supplyAsync(() -> {
            // Convert glob pattern to SQL LIKE pattern
            String likePattern = pattern
                .replace("*", "%")
                .replace("?", "_");
            
            String sql = "SELECT key FROM kv_store WHERE key LIKE ?";

            List<String> keys = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, likePattern);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString("key"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get keys with pattern: " + pattern, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return keys;
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM kv_store";

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int deleted = stmt.executeUpdate();
                LOG.infof("Cleared KV store: %d entries deleted", deleted);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to clear KV store", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM kv_store";

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get KV store size", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closed SQLiteKVStorage");
    }
}
