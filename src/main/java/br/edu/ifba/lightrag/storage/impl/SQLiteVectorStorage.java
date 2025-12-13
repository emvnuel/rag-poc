package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-based implementation of VectorStorage.
 * 
 * <p>Stores vectors as BLOB in Float32 format and performs similarity search
 * using standard SQL cosine similarity calculation. Project isolation is
 * enforced via project_id filtering on all queries.</p>
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Vector storage as Float32 BLOB</li>
 *   <li>Cosine similarity search via SQL</li>
 *   <li>Project isolation via project_id filtering</li>
 *   <li>Batch upsert for efficiency</li>
 *   <li>Memory-efficient chunked batch processing for edge deployment</li>
 * </ul>
 */
public final class SQLiteVectorStorage implements VectorStorage {

    private static final Logger LOG = Logger.getLogger(SQLiteVectorStorage.class);

    /** Default table name for vector storage */
    private static final String DEFAULT_TABLE_NAME = "vectors";
    
    /** Default batch chunk size for memory-efficient processing */
    private static final int DEFAULT_BATCH_CHUNK_SIZE = 500;
    
    /** Edge deployment batch chunk size (smaller for low memory) */
    private static final int EDGE_BATCH_CHUNK_SIZE = 100;

    private final SQLiteConnectionManager connectionManager;
    private final int vectorDimension;
    private final int batchChunkSize;
    private final String tableName;

    /**
     * Creates a new SQLiteVectorStorage with default table name and batch chunk size.
     *
     * @param connectionManager the SQLite connection manager
     * @param vectorDimension the dimension of vectors to store (e.g., 384, 768, 1536)
     */
    public SQLiteVectorStorage(SQLiteConnectionManager connectionManager, int vectorDimension) {
        this(connectionManager, vectorDimension, DEFAULT_TABLE_NAME, DEFAULT_BATCH_CHUNK_SIZE);
    }

    /**
     * Creates a new SQLiteVectorStorage with custom table name and default batch chunk size.
     *
     * @param connectionManager the SQLite connection manager
     * @param vectorDimension the dimension of vectors to store (e.g., 384, 768, 1536)
     * @param tableName the name of the table to use for vector storage
     */
    public SQLiteVectorStorage(SQLiteConnectionManager connectionManager, int vectorDimension, String tableName) {
        this(connectionManager, vectorDimension, tableName, DEFAULT_BATCH_CHUNK_SIZE);
    }

    /**
     * Creates a new SQLiteVectorStorage with custom table name and batch chunk size.
     *
     * @param connectionManager the SQLite connection manager
     * @param vectorDimension the dimension of vectors to store (e.g., 384, 768, 1536)
     * @param tableName the name of the table to use for vector storage
     * @param batchChunkSize size of chunks for batch processing (smaller = less memory)
     */
    public SQLiteVectorStorage(SQLiteConnectionManager connectionManager, int vectorDimension, String tableName, int batchChunkSize) {
        this.connectionManager = connectionManager;
        this.vectorDimension = vectorDimension;
        this.tableName = tableName != null && !tableName.isBlank() ? tableName : DEFAULT_TABLE_NAME;
        this.batchChunkSize = batchChunkSize;
    }

    /**
     * Creates a SQLiteVectorStorage optimized for edge deployment.
     * Uses smaller batch chunk size for lower memory usage.
     *
     * @param connectionManager the SQLite connection manager
     * @param vectorDimension the dimension of vectors to store
     * @return SQLiteVectorStorage configured for edge deployment
     */
    public static SQLiteVectorStorage forEdgeDeployment(SQLiteConnectionManager connectionManager, int vectorDimension) {
        LOG.info("Creating SQLiteVectorStorage for edge deployment (low-memory mode)");
        return new SQLiteVectorStorage(connectionManager, vectorDimension, DEFAULT_TABLE_NAME, EDGE_BATCH_CHUNK_SIZE);
    }

    /**
     * Creates a SQLiteVectorStorage optimized for edge deployment with custom table name.
     * Uses smaller batch chunk size for lower memory usage.
     *
     * @param connectionManager the SQLite connection manager
     * @param vectorDimension the dimension of vectors to store
     * @param tableName the name of the table to use for vector storage
     * @return SQLiteVectorStorage configured for edge deployment
     */
    public static SQLiteVectorStorage forEdgeDeployment(SQLiteConnectionManager connectionManager, int vectorDimension, String tableName) {
        LOG.info("Creating SQLiteVectorStorage for edge deployment (low-memory mode)");
        return new SQLiteVectorStorage(connectionManager, vectorDimension, tableName, EDGE_BATCH_CHUNK_SIZE);
    }

    /**
     * Gets the configured batch chunk size.
     *
     * @return batch chunk size for memory-efficient processing
     */
    public int getBatchChunkSize() {
        return batchChunkSize;
    }

    /**
     * Gets the configured table name.
     *
     * @return table name used for vector storage
     */
    public String getTableName() {
        return tableName;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            // Create table if it doesn't exist (supports custom table names)
            createTableIfNotExists();
            LOG.infof("Initialized SQLiteVectorStorage with dimension %d, table '%s'", vectorDimension, tableName);
        });
    }

    /**
     * Creates the vector table if it doesn't exist.
     * This allows using custom table names beyond the default 'vectors' table.
     */
    private void createTableIfNotExists() {
        String createTableSql = String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                vector BLOB NOT NULL,
                document_id TEXT,
                chunk_index INTEGER,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """, tableName);
        
        String createIndexProjectSql = String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_project_id ON %s(project_id)", tableName, tableName);
        String createIndexTypeSql = String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_type ON %s(type)", tableName, tableName);
        String createIndexProjectTypeSql = String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_project_type ON %s(project_id, type)", tableName, tableName);
        String createIndexDocumentSql = String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_document_id ON %s(document_id)", tableName, tableName);
        
        Connection conn = connectionManager.getWriteConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
            stmt.execute(createIndexProjectSql);
            stmt.execute(createIndexTypeSql);
            stmt.execute(createIndexProjectTypeSql);
            stmt.execute(createIndexDocumentSql);
            LOG.debugf("Ensured table '%s' exists with indexes", tableName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create vector table: " + tableName, e);
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    @Override
    public CompletableFuture<Void> upsert(@NotNull String id, @NotNull Object vector, @NotNull VectorMetadata metadata) {
        return CompletableFuture.runAsync(() -> {
            String sql = String.format("""
                INSERT INTO %s (id, project_id, type, content, vector, document_id, chunk_index, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    type = excluded.type,
                    content = excluded.content,
                    vector = excluded.vector,
                    document_id = excluded.document_id,
                    chunk_index = excluded.chunk_index
                """, tableName);

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                byte[] vectorBytes = vectorToBytes(vector);
                
                stmt.setString(1, id);
                stmt.setString(2, metadata.projectId());
                stmt.setString(3, metadata.type());
                stmt.setString(4, metadata.content());
                stmt.setBytes(5, vectorBytes);
                stmt.setString(6, metadata.documentId());
                stmt.setObject(7, metadata.chunkIndex());
                
                stmt.executeUpdate();
                LOG.debugf("Upserted vector %s", id);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert vector: " + id, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsertBatch(@NotNull List<VectorEntry> entries) {
        return CompletableFuture.runAsync(() -> {
            if (entries.isEmpty()) {
                return;
            }

            String sql = String.format("""
                INSERT INTO %s (id, project_id, type, content, vector, document_id, chunk_index, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(id) DO UPDATE SET
                    type = excluded.type,
                    content = excluded.content,
                    vector = excluded.vector,
                    document_id = excluded.document_id,
                    chunk_index = excluded.chunk_index
                """, tableName);

            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                
                // Process in chunks for memory efficiency
                int totalProcessed = 0;
                for (int i = 0; i < entries.size(); i += batchChunkSize) {
                    int end = Math.min(i + batchChunkSize, entries.size());
                    List<VectorEntry> chunk = entries.subList(i, end);
                    
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        for (VectorEntry entry : chunk) {
                            byte[] vectorBytes = vectorToBytes(entry.vector());
                            
                            stmt.setString(1, entry.id());
                            stmt.setString(2, entry.metadata().projectId());
                            stmt.setString(3, entry.metadata().type());
                            stmt.setString(4, entry.metadata().content());
                            stmt.setBytes(5, vectorBytes);
                            stmt.setString(6, entry.metadata().documentId());
                            stmt.setObject(7, entry.metadata().chunkIndex());
                            
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                    
                    // Commit each chunk to reduce memory pressure
                    conn.commit();
                    totalProcessed += chunk.size();
                    
                    LOG.debugf("Batch upserted chunk %d-%d of %d vectors", i, end, entries.size());
                }
                
                LOG.debugf("Batch upserted %d vectors total (chunk size: %d)", totalProcessed, batchChunkSize);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback after batch upsert failure", rollbackEx);
                }
                throw new RuntimeException("Failed to batch upsert vectors", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    LOG.warn("Failed to reset auto-commit", e);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<List<VectorSearchResult>> query(
            @NotNull Object queryVector, 
            int topK, 
            VectorFilter filter) {
        return CompletableFuture.supplyAsync(() -> {
            // Build query with cosine similarity calculation
            // Cosine similarity = (A · B) / (||A|| * ||B||)
            // Since vectors are normalized, similarity = A · B (dot product)
            
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append(String.format("""
                SELECT id, type, content, document_id, chunk_index, project_id, vector
                FROM %s
                WHERE project_id = ?
                """, tableName));
            
            if (filter != null && filter.type() != null) {
                sqlBuilder.append(" AND type = ?");
            }
            if (filter != null && filter.ids() != null && !filter.ids().isEmpty()) {
                sqlBuilder.append(" AND id IN (");
                sqlBuilder.append("?,".repeat(filter.ids().size()));
                sqlBuilder.setLength(sqlBuilder.length() - 1); // Remove trailing comma
                sqlBuilder.append(")");
            }

            List<VectorSearchResult> results = new ArrayList<>();
            float[] queryVec = toFloatArray(queryVector);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
                int paramIndex = 1;
                stmt.setString(paramIndex++, filter != null ? filter.projectId() : "");
                
                if (filter != null && filter.type() != null) {
                    stmt.setString(paramIndex++, filter.type());
                }
                if (filter != null && filter.ids() != null) {
                    for (String id : filter.ids()) {
                        stmt.setString(paramIndex++, id);
                    }
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        byte[] storedVectorBytes = rs.getBytes("vector");
                        float[] storedVector = bytesToFloatArray(storedVectorBytes);
                        
                        double similarity = cosineSimilarity(queryVec, storedVector);
                        
                        VectorMetadata metadata = new VectorMetadata(
                            rs.getString("type"),
                            rs.getString("content"),
                            rs.getString("document_id"),
                            rs.getObject("chunk_index") != null ? rs.getInt("chunk_index") : null,
                            rs.getString("project_id")
                        );
                        
                        results.add(new VectorSearchResult(
                            rs.getString("id"),
                            similarity,
                            metadata
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to query vectors", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }

            // Sort by similarity (descending) and limit to topK
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (results.size() > topK) {
                return results.subList(0, topK);
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = String.format("DELETE FROM %s WHERE id = ?", tableName);
            
            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                int deleted = stmt.executeUpdate();
                LOG.debugf("Deleted vector %s: %s", id, deleted > 0);
                return deleted > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete vector: " + id, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteBatch(@NotNull List<String> ids) {
        return CompletableFuture.supplyAsync(() -> {
            if (ids.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder(String.format("DELETE FROM %s WHERE id IN (", tableName));
            sql.append("?,".repeat(ids.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < ids.size(); i++) {
                    stmt.setString(i + 1, ids.get(i));
                }
                int deleted = stmt.executeUpdate();
                LOG.debugf("Batch deleted %d vectors", deleted);
                return deleted;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to batch delete vectors", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteEntityEmbeddings(@NotNull String projectId, @NotNull Set<String> entityNames) {
        return CompletableFuture.supplyAsync(() -> {
            if (entityNames.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder(String.format(
                "DELETE FROM %s WHERE project_id = ? AND type = 'entity' AND content IN (", tableName));
            sql.append("?,".repeat(entityNames.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setString(1, projectId);
                int i = 2;
                for (String name : entityNames) {
                    stmt.setString(i++, name);
                }
                int deleted = stmt.executeUpdate();
                LOG.debugf("Deleted %d entity embeddings for project %s", deleted, projectId);
                return deleted;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete entity embeddings", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteChunkEmbeddings(@NotNull String projectId, @NotNull Set<String> chunkIds) {
        return CompletableFuture.supplyAsync(() -> {
            if (chunkIds.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder(String.format(
                "DELETE FROM %s WHERE project_id = ? AND type = 'chunk' AND id IN (", tableName));
            sql.append("?,".repeat(chunkIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setString(1, projectId);
                int i = 2;
                for (String id : chunkIds) {
                    stmt.setString(i++, id);
                }
                int deleted = stmt.executeUpdate();
                LOG.debugf("Deleted %d chunk embeddings for project %s", deleted, projectId);
                return deleted;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete chunk embeddings", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> getChunkIdsByDocumentId(@NotNull String projectId, @NotNull String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = String.format("SELECT id FROM %s WHERE project_id = ? AND document_id = ? AND type = 'chunk'", tableName);
            
            List<String> chunkIds = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, documentId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        chunkIds.add(rs.getString("id"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get chunk IDs for document: " + documentId, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            
            return chunkIds;
        });
    }

    @Override
    public CompletableFuture<VectorEntry> get(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = String.format("SELECT id, type, content, document_id, chunk_index, project_id, vector FROM %s WHERE id = ?", tableName);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        byte[] vectorBytes = rs.getBytes("vector");
                        float[] vector = bytesToFloatArray(vectorBytes);
                        
                        VectorMetadata metadata = new VectorMetadata(
                            rs.getString("type"),
                            rs.getString("content"),
                            rs.getString("document_id"),
                            rs.getObject("chunk_index") != null ? rs.getInt("chunk_index") : null,
                            rs.getString("project_id")
                        );
                        
                        return new VectorEntry(rs.getString("id"), vector, metadata);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get vector: " + id, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            String sql = String.format("DELETE FROM %s", tableName);
            
            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int deleted = stmt.executeUpdate();
                LOG.infof("Cleared all vectors from '%s': %d rows deleted", tableName, deleted);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to clear vectors", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = String.format("SELECT COUNT(*) FROM %s", tableName);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get vector count", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> hasVectors(@NotNull String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = String.format("SELECT EXISTS(SELECT 1 FROM %s WHERE document_id = ?)", tableName);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, documentId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        boolean hasVectors = rs.getBoolean(1);
                        LOG.debugf("Document %s has vectors: %s", documentId, hasVectors);
                        return hasVectors;
                    }
                }
                return false;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check vectors for document: " + documentId, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closed SQLiteVectorStorage");
    }

    // ========== Helper Methods ==========

    /**
     * Converts a vector object to byte array for storage.
     */
    private byte[] vectorToBytes(Object vector) {
        float[] floats = toFloatArray(vector);
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * Converts a byte array back to float array.
     */
    private float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    /**
     * Converts various vector formats to float array.
     */
    private float[] toFloatArray(Object vector) {
        if (vector instanceof float[] arr) {
            return arr;
        } else if (vector instanceof double[] arr) {
            float[] result = new float[arr.length];
            for (int i = 0; i < arr.length; i++) {
                result[i] = (float) arr[i];
            }
            return result;
        } else if (vector instanceof List<?> list) {
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = ((Number) list.get(i)).floatValue();
            }
            return result;
        } else {
            throw new IllegalArgumentException("Unsupported vector type: " + vector.getClass());
        }
    }

    /**
     * Calculates cosine similarity between two vectors.
     * Assumes vectors are normalized (magnitude = 1).
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        double magnitude = Math.sqrt(normA) * Math.sqrt(normB);
        if (magnitude == 0) {
            return 0.0;
        }
        
        return dotProduct / magnitude;
    }
}
