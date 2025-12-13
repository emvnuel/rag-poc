package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.DocStatusStorage;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-based implementation of DocStatusStorage.
 * 
 * <p>Tracks document processing status and metadata using the document_status table.</p>
 */
public final class SQLiteDocStatusStorage implements DocStatusStorage {

    private static final Logger LOG = Logger.getLogger(SQLiteDocStatusStorage.class);

    private final SQLiteConnectionManager connectionManager;

    /**
     * Creates a new SQLiteDocStatusStorage.
     *
     * @param connectionManager the SQLite connection manager
     */
    public SQLiteDocStatusStorage(SQLiteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            LOG.info("Initialized SQLiteDocStatusStorage");
        });
    }

    @Override
    public CompletableFuture<DocumentStatus> getStatus(@NotNull String docId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT doc_id, processing_status, chunk_count, entity_count, relation_count,
                       error_message, created_at, updated_at
                FROM document_status
                WHERE doc_id = ?
                """;

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, docId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return statusFromResultSet(rs);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get status for doc: " + docId, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<DocumentStatus>> getStatuses(@NotNull List<String> docIds) {
        return CompletableFuture.supplyAsync(() -> {
            if (docIds.isEmpty()) {
                return new ArrayList<>();
            }

            StringBuilder sql = new StringBuilder("""
                SELECT doc_id, processing_status, chunk_count, entity_count, relation_count,
                       error_message, created_at, updated_at
                FROM document_status
                WHERE doc_id IN (
                """);
            sql.append("?,".repeat(docIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            List<DocumentStatus> statuses = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < docIds.size(); i++) {
                    stmt.setString(i + 1, docIds.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        statuses.add(statusFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get statuses", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return statuses;
        });
    }

    @Override
    public CompletableFuture<Void> setStatus(@NotNull DocumentStatus status) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO document_status (doc_id, processing_status, chunk_count, entity_count, relation_count,
                                             error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(doc_id) DO UPDATE SET
                    processing_status = excluded.processing_status,
                    chunk_count = excluded.chunk_count,
                    entity_count = excluded.entity_count,
                    relation_count = excluded.relation_count,
                    error_message = excluded.error_message,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, status.docId());
                stmt.setString(2, status.processingStatus().name());
                stmt.setInt(3, status.chunkCount());
                stmt.setInt(4, status.entityCount());
                stmt.setInt(5, status.relationCount());
                stmt.setString(6, status.errorMessage());
                stmt.setString(7, status.createdAt().toString());
                
                stmt.executeUpdate();
                LOG.debugf("Set status for doc %s: %s", status.docId(), status.processingStatus());
            } catch (SQLException e) {
                throw new RuntimeException("Failed to set status for doc: " + status.docId(), e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setStatuses(@NotNull List<DocumentStatus> statuses) {
        return CompletableFuture.runAsync(() -> {
            if (statuses.isEmpty()) {
                return;
            }

            String sql = """
                INSERT INTO document_status (doc_id, processing_status, chunk_count, entity_count, relation_count,
                                             error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(doc_id) DO UPDATE SET
                    processing_status = excluded.processing_status,
                    chunk_count = excluded.chunk_count,
                    entity_count = excluded.entity_count,
                    relation_count = excluded.relation_count,
                    error_message = excluded.error_message,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (DocumentStatus status : statuses) {
                        stmt.setString(1, status.docId());
                        stmt.setString(2, status.processingStatus().name());
                        stmt.setInt(3, status.chunkCount());
                        stmt.setInt(4, status.entityCount());
                        stmt.setInt(5, status.relationCount());
                        stmt.setString(6, status.errorMessage());
                        stmt.setString(7, status.createdAt().toString());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
                conn.commit();
                LOG.debugf("Batch set %d statuses", statuses.size());
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to batch set statuses", e);
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
    public CompletableFuture<Boolean> deleteStatus(@NotNull String docId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM document_status WHERE doc_id = ?";

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, docId);
                int deleted = stmt.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete status for doc: " + docId, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteStatuses(@NotNull List<String> docIds) {
        return CompletableFuture.supplyAsync(() -> {
            if (docIds.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder("DELETE FROM document_status WHERE doc_id IN (");
            sql.append("?,".repeat(docIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < docIds.size(); i++) {
                    stmt.setString(i + 1, docIds.get(i));
                }
                return stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to batch delete statuses", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<List<DocumentStatus>> getAllStatuses() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT doc_id, processing_status, chunk_count, entity_count, relation_count,
                       error_message, created_at, updated_at
                FROM document_status
                """;

            List<DocumentStatus> statuses = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    statuses.add(statusFromResultSet(rs));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get all statuses", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return statuses;
        });
    }

    @Override
    public CompletableFuture<List<DocumentStatus>> getStatusesByProcessingStatus(@NotNull ProcessingStatus processingStatus) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT doc_id, processing_status, chunk_count, entity_count, relation_count,
                       error_message, created_at, updated_at
                FROM document_status
                WHERE processing_status = ?
                """;

            List<DocumentStatus> statuses = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, processingStatus.name());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        statuses.add(statusFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get statuses by processing status", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return statuses;
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM document_status";

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int deleted = stmt.executeUpdate();
                LOG.infof("Cleared document status table: %d entries deleted", deleted);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to clear document status table", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM document_status";

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get document status count", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closed SQLiteDocStatusStorage");
    }

    // ========== Helper Methods ==========

    private DocumentStatus statusFromResultSet(ResultSet rs) throws SQLException {
        String createdAtStr = rs.getString("created_at");
        String updatedAtStr = rs.getString("updated_at");
        
        return new DocumentStatus(
            rs.getString("doc_id"),
            ProcessingStatus.valueOf(rs.getString("processing_status")),
            parseInstant(createdAtStr),
            parseInstant(updatedAtStr),
            null, // filePath not stored in this table
            rs.getInt("chunk_count"),
            rs.getInt("entity_count"),
            rs.getInt("relation_count"),
            rs.getString("error_message")
        );
    }

    private Instant parseInstant(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return Instant.now();
        }
        try {
            // SQLite datetime format: YYYY-MM-DD HH:MM:SS
            return Instant.parse(dateStr.replace(" ", "T") + "Z");
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
