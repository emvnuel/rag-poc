package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.ExtractionCache;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.utils.TransientSQLExceptionPredicate;
import br.edu.ifba.shared.UuidUtils;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL implementation of ExtractionCacheStorage.
 * Uses the rag.extraction_cache table for persistent storage.
 */
@ApplicationScoped
@IfBuildProperty(name = "lightrag.storage.backend", stringValue = "postgresql", enableIfMissing = true)
public class PgExtractionCacheStorage implements ExtractionCacheStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(PgExtractionCacheStorage.class);
    
    private static final TransientSQLExceptionPredicate TRANSIENT_PREDICATE = new TransientSQLExceptionPredicate();
    
    private static final String TABLE_NAME = "rag.extraction_cache";
    
    private static final String INSERT_SQL = """
        INSERT INTO %s (id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (project_id, cache_type, content_hash) DO UPDATE SET
            result = EXCLUDED.result,
            tokens_used = EXCLUDED.tokens_used,
            created_at = EXCLUDED.created_at
        RETURNING id
        """.formatted(TABLE_NAME);
    
    private static final String SELECT_BY_HASH_SQL = """
        SELECT id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at
        FROM %s
        WHERE project_id = ? AND cache_type = ? AND content_hash = ?
        """.formatted(TABLE_NAME);
    
    private static final String SELECT_BY_CHUNK_SQL = """
        SELECT id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at
        FROM %s
        WHERE project_id = ? AND chunk_id = ?
        """.formatted(TABLE_NAME);
    
    private static final String DELETE_BY_PROJECT_SQL = """
        DELETE FROM %s WHERE project_id = ?
        """.formatted(TABLE_NAME);
    
    @Inject
    DataSource dataSource;
    
    @Override
    public CompletableFuture<Void> initialize() {
        logger.info("PgExtractionCacheStorage initialized");
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    @Retry(retryOn = SQLException.class, abortOn = {}, 
           maxRetries = 3, delay = 200, jitter = 100)
    public CompletableFuture<String> store(
        @NotNull String projectId,
        @NotNull CacheType cacheType,
        @Nullable String chunkId,
        @NotNull String contentHash,
        @NotNull String result,
        @Nullable Integer tokensUsed
    ) {
        return CompletableFuture.supplyAsync(() -> {
            UUID id = UuidUtils.randomV7();
            UUID projectUuid = UUID.fromString(projectId);
            UUID chunkUuid = chunkId != null ? UUID.fromString(chunkId) : null;
            Instant now = Instant.now();
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
                
                stmt.setObject(1, id);
                stmt.setObject(2, projectUuid);
                stmt.setString(3, cacheType.name());
                if (chunkUuid != null) {
                    stmt.setObject(4, chunkUuid);
                } else {
                    stmt.setNull(4, Types.OTHER);
                }
                stmt.setString(5, contentHash);
                stmt.setString(6, result);
                if (tokensUsed != null) {
                    stmt.setInt(7, tokensUsed);
                } else {
                    stmt.setNull(7, Types.INTEGER);
                }
                stmt.setTimestamp(8, Timestamp.from(now));
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        UUID returnedId = (UUID) rs.getObject(1);
                        logger.debug("Stored extraction cache entry: {} for project: {}", returnedId, projectId);
                        return returnedId.toString();
                    }
                }
                
                return id.toString();
                
            } catch (SQLException e) {
                if (TRANSIENT_PREDICATE.test(e)) {
                    throw new RuntimeException("Transient error storing extraction cache", e);
                }
                logger.error("Failed to store extraction cache: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to store extraction cache", e);
            }
        });
    }
    
    @Override
    @Retry(retryOn = SQLException.class, abortOn = {},
           maxRetries = 3, delay = 200, jitter = 100)
    public CompletableFuture<Optional<ExtractionCache>> get(
        @NotNull String projectId,
        @NotNull CacheType cacheType,
        @NotNull String contentHash
    ) {
        return CompletableFuture.supplyAsync(() -> {
            UUID projectUuid = UUID.fromString(projectId);
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SELECT_BY_HASH_SQL)) {
                
                stmt.setObject(1, projectUuid);
                stmt.setString(2, cacheType.name());
                stmt.setString(3, contentHash);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSet(rs));
                    }
                }
                
                return Optional.empty();
                
            } catch (SQLException e) {
                if (TRANSIENT_PREDICATE.test(e)) {
                    throw new RuntimeException("Transient error getting extraction cache", e);
                }
                logger.error("Failed to get extraction cache: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to get extraction cache", e);
            }
        });
    }
    
    @Override
    @Retry(retryOn = SQLException.class, abortOn = {},
           maxRetries = 3, delay = 200, jitter = 100)
    public CompletableFuture<List<ExtractionCache>> getByChunkId(
        @NotNull String projectId,
        @NotNull String chunkId
    ) {
        return CompletableFuture.supplyAsync(() -> {
            UUID projectUuid = UUID.fromString(projectId);
            UUID chunkUuid = UUID.fromString(chunkId);
            List<ExtractionCache> results = new ArrayList<>();
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SELECT_BY_CHUNK_SQL)) {
                
                stmt.setObject(1, projectUuid);
                stmt.setObject(2, chunkUuid);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapResultSet(rs));
                    }
                }
                
                return results;
                
            } catch (SQLException e) {
                if (TRANSIENT_PREDICATE.test(e)) {
                    throw new RuntimeException("Transient error getting extraction cache by chunk", e);
                }
                logger.error("Failed to get extraction cache by chunk: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to get extraction cache by chunk", e);
            }
        });
    }
    
    @Override
    @Retry(retryOn = SQLException.class, abortOn = {},
           maxRetries = 3, delay = 200, jitter = 100)
    public CompletableFuture<Integer> deleteByProject(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            UUID projectUuid = UUID.fromString(projectId);
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(DELETE_BY_PROJECT_SQL)) {
                
                stmt.setObject(1, projectUuid);
                int deleted = stmt.executeUpdate();
                
                logger.info("Deleted {} extraction cache entries for project: {}", deleted, projectId);
                return deleted;
                
            } catch (SQLException e) {
                if (TRANSIENT_PREDICATE.test(e)) {
                    throw new RuntimeException("Transient error deleting extraction cache", e);
                }
                logger.error("Failed to delete extraction cache: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to delete extraction cache", e);
            }
        });
    }
    
    @Override
    public void close() {
        logger.debug("PgExtractionCacheStorage closed");
    }
    
    private ExtractionCache mapResultSet(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        UUID projectId = (UUID) rs.getObject("project_id");
        String cacheTypeStr = rs.getString("cache_type");
        UUID chunkId = (UUID) rs.getObject("chunk_id");
        String contentHash = rs.getString("content_hash");
        String result = rs.getString("result");
        Integer tokensUsed = rs.getObject("tokens_used") != null ? rs.getInt("tokens_used") : null;
        Timestamp createdAt = rs.getTimestamp("created_at");
        
        return new ExtractionCache(
            id,
            projectId,
            CacheType.valueOf(cacheTypeStr),
            chunkId,
            contentHash,
            result,
            tokensUsed,
            createdAt.toInstant()
        );
    }
}
