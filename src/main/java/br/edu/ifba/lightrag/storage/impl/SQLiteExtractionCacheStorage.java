package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.ExtractionCache;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-based implementation of ExtractionCacheStorage.
 * 
 * <p>Stores LLM extraction results for rebuild capability.
 * Uses the extraction_cache table with unique constraint on (project_id, cache_type, content_hash).</p>
 */
public final class SQLiteExtractionCacheStorage implements ExtractionCacheStorage {

    private static final Logger LOG = Logger.getLogger(SQLiteExtractionCacheStorage.class);

    private final SQLiteConnectionManager connectionManager;

    /**
     * Creates a new SQLiteExtractionCacheStorage.
     *
     * @param connectionManager the SQLite connection manager
     */
    public SQLiteExtractionCacheStorage(SQLiteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            LOG.info("Initialized SQLiteExtractionCacheStorage");
        });
    }

    @Override
    public CompletableFuture<String> store(
            @NotNull String projectId,
            @NotNull CacheType cacheType,
            @Nullable String chunkId,
            @NotNull String contentHash,
            @NotNull String result,
            @Nullable Integer tokensUsed) {
        return CompletableFuture.supplyAsync(() -> {
            String id = UUID.randomUUID().toString();
            String sql = """
                INSERT INTO extraction_cache (id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(project_id, cache_type, content_hash) DO UPDATE SET
                    result = excluded.result,
                    tokens_used = excluded.tokens_used
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, projectId);
                stmt.setString(3, cacheType.name());
                stmt.setString(4, chunkId);
                stmt.setString(5, contentHash);
                stmt.setString(6, result);
                stmt.setObject(7, tokensUsed);
                
                stmt.executeUpdate();
                LOG.debugf("Stored extraction cache entry for project %s, type %s", projectId, cacheType);
                return id;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to store extraction cache", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ExtractionCache>> get(
            @NotNull String projectId,
            @NotNull CacheType cacheType,
            @NotNull String contentHash) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at
                FROM extraction_cache
                WHERE project_id = ? AND cache_type = ? AND content_hash = ?
                """;

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, cacheType.name());
                stmt.setString(3, contentHash);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(cacheFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get extraction cache", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<ExtractionCache>> getByChunkId(
            @NotNull String projectId,
            @NotNull String chunkId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT id, project_id, cache_type, chunk_id, content_hash, result, tokens_used, created_at
                FROM extraction_cache
                WHERE project_id = ? AND chunk_id = ?
                """;

            List<ExtractionCache> caches = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, chunkId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        caches.add(cacheFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get extraction cache by chunk ID", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return caches;
        });
    }

    @Override
    public CompletableFuture<Integer> deleteByProject(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM extraction_cache WHERE project_id = ?";

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                int deleted = stmt.executeUpdate();
                LOG.debugf("Deleted %d extraction cache entries for project %s", deleted, projectId);
                return deleted;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete extraction cache by project", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public void close() {
        LOG.info("Closed SQLiteExtractionCacheStorage");
    }

    // ========== Helper Methods ==========

    private ExtractionCache cacheFromResultSet(ResultSet rs) throws SQLException {
        String chunkIdStr = rs.getString("chunk_id");
        String createdAtStr = rs.getString("created_at");
        
        return ExtractionCache.builder()
            .id(UUID.fromString(rs.getString("id")))
            .projectId(UUID.fromString(rs.getString("project_id")))
            .cacheType(CacheType.valueOf(rs.getString("cache_type")))
            .chunkId(chunkIdStr != null ? UUID.fromString(chunkIdStr) : null)
            .contentHash(rs.getString("content_hash"))
            .result(rs.getString("result"))
            .tokensUsed(rs.getObject("tokens_used") != null ? rs.getInt("tokens_used") : null)
            .createdAt(createdAtStr != null ? Instant.parse(createdAtStr.replace(" ", "T") + "Z") : Instant.now())
            .build();
    }
}
