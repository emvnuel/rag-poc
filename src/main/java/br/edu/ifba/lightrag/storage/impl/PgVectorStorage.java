package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.utils.TransientSQLExceptionPredicate;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.smallrye.faulttolerance.api.RetryWhen;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PostgreSQL + pgvector implementation of VectorStorage.
 * Uses pgvector extension for efficient similarity search.
 * Adapted for Quarkus with CDI and managed DataSource.
 * 
 * Setup requirements:
 * 1. PostgreSQL with pgvector extension installed
 * 2. CREATE EXTENSION vector;
 * 3. Table will be created automatically on initialize()
 */
@ApplicationScoped
@IfBuildProperty(name = "lightrag.storage.backend", stringValue = "postgresql", enableIfMissing = true)
public class PgVectorStorage implements VectorStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(PgVectorStorage.class);
    
    @Inject
    DataSource dataSource;
    
    @ConfigProperty(name = "lightrag.vector.table.name", defaultValue = "lightrag_vectors")
    String tableName;
    
    @ConfigProperty(name = "lightrag.vector.dimension", defaultValue = "768")
    int dimension;
    
    // HNSW index configuration (aligned with official LightRAG Python implementation)
    @ConfigProperty(name = "lightrag.vector.index.type", defaultValue = "hnsw")
    String indexType;
    
    @ConfigProperty(name = "lightrag.vector.index.hnsw.m", defaultValue = "16")
    int hnswM;
    
    @ConfigProperty(name = "lightrag.vector.index.hnsw.ef-construction", defaultValue = "64")
    int hnswEfConstruction;
    
    // IVFFLAT index configuration (alternative for larger datasets)
    @ConfigProperty(name = "lightrag.vector.index.ivfflat.lists", defaultValue = "100")
    int ivfflatLists;
    
    private final ExecutorService executor;
    
    /**
     * Default constructor for CDI.
     */
    public PgVectorStorage() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                logger.info("Initializing pgvector storage for table: {}", tableName);
                
                // Create pgvector extension if not exists
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                
                // Create table with halfvec column (more efficient for high-dimensional vectors)
                // halfvec uses 2 bytes per dimension vs 4 bytes for vector
                // Supported up to 4,000 dimensions with HNSW index
                String createTableSql = String.format("""
                    CREATE TABLE IF NOT EXISTS rag.%s (
                        id UUID PRIMARY KEY,
                        vector halfvec(%d) NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        document_id UUID,
                        chunk_index INTEGER,
                        project_id UUID,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """, tableName, dimension);
                
                stmt.execute(createTableSql);
                
                // Add foreign key constraints for document_id and project_id
                // Note: Using rag schema for application tables (documents, projects, vectors)
                // ag_catalog is only used by Apache AGE for graph metadata (ag_graph, ag_label)
                // This is done separately so initialization doesn't fail if tables don't exist yet
                
                // Add document_id foreign key with CASCADE delete
                String documentFkName = "fk_" + tableName + "_document";
                try {
                    String addDocumentFkSql = String.format("""
                        DO $$
                        BEGIN
                            IF NOT EXISTS (
                                SELECT 1 FROM pg_constraint 
                                WHERE conname = '%s'
                                AND conrelid = 'rag.%s'::regclass
                            ) THEN
                                ALTER TABLE rag.%s 
                                ADD CONSTRAINT %s 
                                FOREIGN KEY (document_id) REFERENCES rag.documents(id) ON DELETE CASCADE;
                            END IF;
                        END
                        $$;
                        """, documentFkName, tableName, tableName, documentFkName);
                    stmt.execute(addDocumentFkSql);
                    logger.debug("Foreign key constraint {} added to {}", documentFkName, tableName);
                } catch (SQLException e) {
                    logger.warn("Could not add document_id foreign key constraint (document table may not exist yet): {}", e.getMessage());
                }
                
                // Add project_id foreign key with CASCADE delete
                String projectFkName = "fk_" + tableName + "_project";
                try {
                    String addProjectFkSql = String.format("""
                        DO $$
                        BEGIN
                            IF NOT EXISTS (
                                SELECT 1 FROM pg_constraint 
                                WHERE conname = '%s'
                                AND conrelid = 'rag.%s'::regclass
                            ) THEN
                                ALTER TABLE rag.%s 
                                ADD CONSTRAINT %s 
                                FOREIGN KEY (project_id) REFERENCES rag.projects(id) ON DELETE CASCADE;
                            END IF;
                        END
                        $$;
                        """, projectFkName, tableName, tableName, projectFkName);
                    stmt.execute(addProjectFkSql);
                    logger.debug("Foreign key constraint {} added to {}", projectFkName, tableName);
                } catch (SQLException e) {
                    logger.warn("Could not add project_id foreign key constraint (project table may not exist yet): {}", e.getMessage());
                }
                
                // Create index for vector similarity search
                // Using halfvec_cosine_ops for halfvec type (cosine distance)
                // Supports HNSW (default) or IVFFLAT index types with configurable parameters
                String createIndexSql;
                if ("ivfflat".equalsIgnoreCase(indexType)) {
                    // IVFFLAT: Better for larger datasets with less memory
                    // lists: number of clusters (sqrt(n) to n/1000 recommended)
                    createIndexSql = String.format(
                        "CREATE INDEX IF NOT EXISTS %s_vector_idx ON rag.%s USING ivfflat (vector halfvec_cosine_ops) WITH (lists = %d)",
                        tableName, tableName, ivfflatLists
                    );
                    logger.info("Creating IVFFLAT index with lists={}", ivfflatLists);
                } else {
                    // HNSW: Better recall/performance, more memory
                    // m: max connections per node (default 16)
                    // ef_construction: build-time search width (default 64)
                    createIndexSql = String.format(
                        "CREATE INDEX IF NOT EXISTS %s_vector_idx ON rag.%s USING hnsw (vector halfvec_cosine_ops) WITH (m = %d, ef_construction = %d)",
                        tableName, tableName, hnswM, hnswEfConstruction
                    );
                    logger.info("Creating HNSW index with m={}, ef_construction={}", hnswM, hnswEfConstruction);
                }
                executeIndexCreation(stmt, createIndexSql, tableName + "_vector_idx");
                
                // Create index on type for filtered queries
                String createTypeIndexSql = String.format(
                    "CREATE INDEX IF NOT EXISTS %s_type_idx ON rag.%s (type)",
                    tableName, tableName
                );
                executeIndexCreation(stmt, createTypeIndexSql, tableName + "_type_idx");
                
                logger.info("PgVector storage initialized successfully for table: {}", tableName);
                
            } catch (SQLException e) {
                logger.error("Failed to initialize pgvector storage", e);
                throw new RuntimeException("Failed to initialize pgvector storage", e);
            }
        }, executor);
    }
    
    /**
     * Executes index creation with handling for concurrent duplicate creation.
     * PostgreSQL's CREATE INDEX IF NOT EXISTS can still fail with duplicate key errors
     * in concurrent scenarios due to a race condition in the catalog update.
     * 
     * @param stmt the statement to execute on
     * @param sql the CREATE INDEX SQL
     * @param indexName the index name for logging
     */
    private void executeIndexCreation(Statement stmt, String sql, String indexName) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            // SQLSTATE 23505 = unique_violation (duplicate key)
            // This can happen with concurrent CREATE INDEX IF NOT EXISTS due to race condition
            if ("23505".equals(e.getSQLState())) {
                logger.debug("Index {} already exists (concurrent creation), ignoring", indexName);
            } else {
                throw e;
            }
        }
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsert(
        @NotNull String id, 
        @NotNull Object vector, 
        @NotNull VectorMetadata metadata
    ) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = String.format("""
                    INSERT INTO rag.%s (id, vector, type, content, document_id, chunk_index, project_id)
                    VALUES (?, ?::halfvec, ?, ?, ?::uuid, ?, ?::uuid)
                    ON CONFLICT (id) DO UPDATE SET
                        vector = EXCLUDED.vector,
                        type = EXCLUDED.type,
                        content = EXCLUDED.content,
                        document_id = EXCLUDED.document_id,
                        chunk_index = EXCLUDED.chunk_index,
                        project_id = EXCLUDED.project_id
                    """, tableName);
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setObject(1, UUID.fromString(id));
                    pstmt.setString(2, vectorToString(vector));
                    pstmt.setString(3, metadata.type());
                    pstmt.setString(4, metadata.content());
                    if (metadata.documentId() != null) {
                        pstmt.setObject(5, UUID.fromString(metadata.documentId()));
                    } else {
                        pstmt.setNull(5, java.sql.Types.OTHER);
                    }
                    pstmt.setInt(6, metadata.chunkIndex() != null ? metadata.chunkIndex() : 0);
                    if (metadata.projectId() != null) {
                        pstmt.setObject(7, UUID.fromString(metadata.projectId()));
                    } else {
                        pstmt.setNull(7, java.sql.Types.OTHER);
                    }
                    
                    try {
                        pstmt.executeUpdate();
                    } catch (SQLException e) {
                        // Check if it's a unique constraint violation (SQL state 23505)
                        if ("23505".equals(e.getSQLState())) {
                            logger.warn("Duplicate vector detected for id: {}, document_id: {}, chunk_index: {} - skipping insert", 
                                       id, metadata.documentId(), metadata.chunkIndex());
                            // Don't throw - this is expected behavior with the uniqueness constraint
                            return;
                        }
                        // Re-throw other SQL exceptions
                        throw e;
                    }
                }
                
            } catch (SQLException e) {
                logger.error("Failed to upsert vector: {}", id, e);
                throw new RuntimeException("Failed to upsert vector", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsertBatch(@NotNull List<VectorEntry> entries) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                String sql = String.format("""
                    INSERT INTO rag.%s (id, vector, type, content, document_id, chunk_index, project_id)
                    VALUES (?, ?::halfvec, ?, ?, ?::uuid, ?, ?::uuid)
                    ON CONFLICT (id) DO UPDATE SET
                        vector = EXCLUDED.vector,
                        type = EXCLUDED.type,
                        content = EXCLUDED.content,
                        document_id = EXCLUDED.document_id,
                        chunk_index = EXCLUDED.chunk_index,
                        project_id = EXCLUDED.project_id
                    """, tableName);
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    int duplicateCount = 0;
                    for (VectorEntry entry : entries) {
                        pstmt.setObject(1, UUID.fromString(entry.id()));
                        pstmt.setString(2, vectorToString(entry.vector()));
                        pstmt.setString(3, entry.metadata().type());
                        pstmt.setString(4, entry.metadata().content());
                        if (entry.metadata().documentId() != null) {
                            pstmt.setObject(5, UUID.fromString(entry.metadata().documentId()));
                        } else {
                            pstmt.setNull(5, java.sql.Types.OTHER);
                        }
                        pstmt.setInt(6, entry.metadata().chunkIndex() != null ? entry.metadata().chunkIndex() : 0);
                        if (entry.metadata().projectId() != null) {
                            pstmt.setObject(7, UUID.fromString(entry.metadata().projectId()));
                        } else {
                            pstmt.setNull(7, java.sql.Types.OTHER);
                        }
                        pstmt.addBatch();
                    }
                    
                    try {
                        pstmt.executeBatch();
                        conn.commit();
                        logger.debug("Upserted {} vectors", entries.size());
                    } catch (SQLException e) {
                        // Check if it's a unique constraint violation
                        if ("23505".equals(e.getSQLState())) {
                            duplicateCount++;
                            logger.warn("Batch upsert encountered {} duplicate vectors - constraint prevented duplicates", duplicateCount);
                            conn.rollback();
                            // Don't throw - log and continue
                            return;
                        }
                        // Re-throw other SQL exceptions
                        throw e;
                    }
                }
                
            } catch (SQLException e) {
                logger.error("Failed to batch upsert vectors", e);
                throw new RuntimeException("Failed to batch upsert vectors", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<VectorSearchResult>> query(
        @NotNull Object queryVector, 
        int topK, 
        VectorFilter filter
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<VectorSearchResult> results = new ArrayList<>();
            
            try (Connection conn = dataSource.getConnection()) {
                // Build query with direct project_id filtering (no JOIN needed)
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(String.format("""
                    SELECT v.id, v.type, v.content, v.document_id, v.chunk_index, v.project_id,
                           1 - (v.vector <=> ?::halfvec) AS similarity
                    FROM rag.%s v
                    WHERE 1=1
                    """, tableName));
                
                // Filter by type if provided
                if (filter != null && filter.type() != null) {
                    sqlBuilder.append(" AND v.type = ?");
                }
                
                // Filter by projectId directly on vector table
                if (filter != null && filter.projectId() != null) {
                    sqlBuilder.append(" AND v.project_id = ?::uuid");
                }
                
                sqlBuilder.append(" ORDER BY v.vector <=> ?::halfvec");
                sqlBuilder.append(" LIMIT ?");
                
                try (PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {
                    String vectorStr = vectorToString(queryVector);
                    
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, vectorStr);
                    
                    if (filter != null && filter.type() != null) {
                        pstmt.setString(paramIndex++, filter.type());
                    }
                    
                    if (filter != null && filter.projectId() != null) {
                        pstmt.setString(paramIndex++, filter.projectId());
                    }
                    
                    pstmt.setString(paramIndex++, vectorStr);
                    pstmt.setInt(paramIndex, topK);
                    
                    ResultSet rs = pstmt.executeQuery();
                    
                    while (rs.next()) {
                        VectorMetadata metadata = new VectorMetadata(
                            rs.getString("type"),
                            rs.getString("content"),
                            rs.getString("document_id"),
                            rs.getInt("chunk_index"),
                            rs.getString("project_id")
                        );
                        
                        results.add(new VectorSearchResult(
                            rs.getString("id"),
                            rs.getDouble("similarity"),
                            metadata
                        ));
                    }
                }
                
            } catch (SQLException e) {
                logger.error("Failed to query vectors", e);
                throw new RuntimeException("Failed to query vectors", e);
            }
            
            return results;
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Boolean> delete(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = String.format("DELETE FROM rag.%s WHERE id = ?", tableName);
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setObject(1, UUID.fromString(id));
                    int deleted = pstmt.executeUpdate();
                    return deleted > 0;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to delete vector: {}", id, e);
                return false;
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Integer> deleteBatch(@NotNull List<String> ids) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                String sql = String.format("DELETE FROM rag.%s WHERE id = ?", tableName);
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (String id : ids) {
                        pstmt.setObject(1, UUID.fromString(id));
                        pstmt.addBatch();
                    }
                    
                    int[] results = pstmt.executeBatch();
                    conn.commit();
                    
                    int totalDeleted = 0;
                    for (int result : results) {
                        if (result > 0) totalDeleted++;
                    }
                    
                    return totalDeleted;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to batch delete vectors", e);
                return 0;
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<VectorEntry> get(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = String.format(
                    "SELECT id, vector, type, content, document_id, chunk_index, project_id FROM rag.%s WHERE id = ?",
                    tableName
                );
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setObject(1, UUID.fromString(id));
                    
                    ResultSet rs = pstmt.executeQuery();
                    
                    if (rs.next()) {
                        VectorMetadata metadata = new VectorMetadata(
                            rs.getString("type"),
                            rs.getString("content"),
                            rs.getString("document_id"),
                            rs.getInt("chunk_index"),
                            rs.getString("project_id")
                        );
                        
                        String vectorStr = rs.getString("vector");
                        Object vector = parseVectorString(vectorStr);
                        
                        return new VectorEntry(rs.getString("id"), vector, metadata);
                    }
                    
                    return null;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to get vector: {}", id, e);
                return null;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                stmt.execute(String.format("TRUNCATE TABLE rag.%s", tableName));
                logger.info("Cleared all vectors from table: {}", tableName);
                
            } catch (SQLException e) {
                logger.error("Failed to clear vectors", e);
                throw new RuntimeException("Failed to clear vectors", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Long> size() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                ResultSet rs = stmt.executeQuery(
                    String.format("SELECT COUNT(*) FROM rag.%s", tableName)
                );
                
                if (rs.next()) {
                    return rs.getLong(1);
                }
                
                return 0L;
                
            } catch (SQLException e) {
                logger.error("Failed to get size", e);
                return 0L;
            }
        }, executor);
    }
    
    @Override
    public void close() throws Exception {
        executor.shutdown();
        // DataSource is managed by Quarkus, no need to close
    }
    
    /**
     * Checks if a document already has vectors in the database.
     * This is used to prevent duplicate processing and detect race conditions.
     * 
     * @param documentId The document UUID
     * @return CompletableFuture<Boolean> true if vectors exist, false otherwise
     */
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Boolean> hasVectors(String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = String.format(
                    "SELECT COUNT(*) > 0 FROM rag.%s WHERE document_id = ?::uuid AND type = 'chunk'",
                    tableName
                );
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, documentId);
                    ResultSet rs = pstmt.executeQuery();
                    
                    if (rs.next()) {
                        boolean hasVectors = rs.getBoolean(1);
                        logger.debug("Document {} has vectors: {}", documentId, hasVectors);
                        return hasVectors;
                    }
                    
                    return false;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to check vectors for document: {}", documentId, e);
                return false;
            }
        }, executor);
    }
    
    // ========== Batch Delete Operations (spec-007) ==========
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Integer> deleteEntityEmbeddings(@NotNull String projectId, @NotNull java.util.Set<String> entityNames) {
        if (entityNames == null || entityNames.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // Delete vectors where type='entity' and content matches any entity name
                // Entity embeddings store the entity name in the 'content' field
                StringBuilder sql = new StringBuilder();
                sql.append(String.format(
                    "DELETE FROM rag.%s WHERE project_id = ?::uuid AND type = 'entity' AND content IN (",
                    tableName
                ));
                
                // Build placeholders for IN clause
                String[] placeholders = new String[entityNames.size()];
                java.util.Arrays.fill(placeholders, "?");
                sql.append(String.join(",", placeholders));
                sql.append(")");
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, projectId);
                    
                    for (String entityName : entityNames) {
                        pstmt.setString(paramIndex++, entityName);
                    }
                    
                    int deleted = pstmt.executeUpdate();
                    logger.debug("Deleted {} entity embeddings for project {}", deleted, projectId);
                    return deleted;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to delete entity embeddings for project: {}", projectId, e);
                throw new RuntimeException("Failed to delete entity embeddings", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Integer> deleteChunkEmbeddings(@NotNull String projectId, @NotNull java.util.Set<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // Delete vectors where type='chunk' and id matches any chunk ID
                StringBuilder sql = new StringBuilder();
                sql.append(String.format(
                    "DELETE FROM rag.%s WHERE project_id = ?::uuid AND type = 'chunk' AND id IN (",
                    tableName
                ));
                
                // Build placeholders for IN clause
                String[] placeholders = new String[chunkIds.size()];
                java.util.Arrays.fill(placeholders, "?::uuid");
                sql.append(String.join(",", placeholders));
                sql.append(")");
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, projectId);
                    
                    for (String chunkId : chunkIds) {
                        pstmt.setObject(paramIndex++, UUID.fromString(chunkId));
                    }
                    
                    int deleted = pstmt.executeUpdate();
                    logger.debug("Deleted {} chunk embeddings for project {}", deleted, projectId);
                    return deleted;
                }
                
            } catch (SQLException e) {
                logger.error("Failed to delete chunk embeddings for project: {}", projectId, e);
                throw new RuntimeException("Failed to delete chunk embeddings", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<String>> getChunkIdsByDocumentId(@NotNull String projectId, @NotNull String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> chunkIds = new ArrayList<>();
            
            try (Connection conn = dataSource.getConnection()) {
                String sql = String.format(
                    "SELECT id FROM rag.%s WHERE project_id = ?::uuid AND document_id = ?::uuid AND type = 'chunk'",
                    tableName
                );
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, projectId);
                    pstmt.setString(2, documentId);
                    
                    ResultSet rs = pstmt.executeQuery();
                    
                    while (rs.next()) {
                        chunkIds.add(rs.getString("id"));
                    }
                    
                    logger.debug("Found {} chunks for document {} in project {}", chunkIds.size(), documentId, projectId);
                }
                
            } catch (SQLException e) {
                logger.error("Failed to get chunk IDs for document: {} in project: {}", documentId, projectId, e);
                throw new RuntimeException("Failed to get chunk IDs by document ID", e);
            }
            
            return chunkIds;
        }, executor);
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Converts a vector object (array or base64) to PostgreSQL array string format.
     */
    private String vectorToString(Object vector) {
        double[] arr;
        
        if (vector instanceof double[] doubleArr) {
            arr = doubleArr;
        } else if (vector instanceof float[] floatArr) {
            arr = new double[floatArr.length];
            for (int i = 0; i < floatArr.length; i++) {
                arr[i] = floatArr[i];
            }
        } else if (vector instanceof List<?> list) {
            arr = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = ((Number) list.get(i)).doubleValue();
            }
        } else if (vector instanceof String base64Str) {
            // Decode base64 to byte array, then to double array
            byte[] bytes = Base64.getDecoder().decode(base64Str);
            arr = new double[bytes.length / 8];
            for (int i = 0; i < arr.length; i++) {
                long bits = 0;
                for (int j = 0; j < 8; j++) {
                    bits |= ((long) bytes[i * 8 + j] & 0xFF) << (j * 8);
                }
                arr[i] = Double.longBitsToDouble(bits);
            }
        } else {
            throw new IllegalArgumentException("Unsupported vector type: " + vector.getClass());
        }
        
        // Convert to PostgreSQL array format: [1.0, 2.0, 3.0]
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        
        return sb.toString();
    }
    
    /**
     * Parses PostgreSQL vector string to double array.
     */
    private double[] parseVectorString(String vectorStr) {
        // Remove brackets and split
        vectorStr = vectorStr.substring(1, vectorStr.length() - 1);
        String[] parts = vectorStr.split(",");
        
        double[] arr = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Double.parseDouble(parts[i].trim());
        }
        
        return arr;
    }
    

}
