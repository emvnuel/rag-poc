package br.edu.ifba.lightrag.storage;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.ExtractionCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage interface for LLM extraction result caching.
 * Enables knowledge graph rebuild without re-calling the LLM.
 * 
 * <p>Contract:</p>
 * <ul>
 *   <li>MUST cascade delete on project deletion</li>
 *   <li>MUST maintain unique constraint on (projectId, cacheType, contentHash)</li>
 *   <li>chunk_id FK SHOULD SET NULL on chunk deletion (orphan is OK)</li>
 * </ul>
 */
public interface ExtractionCacheStorage extends AutoCloseable {
    
    /**
     * Initializes the storage (creates tables if needed).
     *
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Stores an extraction result.
     *
     * @param projectId the project ID (required)
     * @param cacheType the type of cached result (required)
     * @param chunkId the source chunk ID (optional)
     * @param contentHash SHA-256 hash of input content (required)
     * @param result the raw LLM response (required)
     * @param tokensUsed LLM tokens consumed (optional)
     * @return CompletableFuture with the cache entry ID
     */
    CompletableFuture<String> store(
        @NotNull String projectId,
        @NotNull CacheType cacheType,
        @Nullable String chunkId,
        @NotNull String contentHash,
        @NotNull String result,
        @Nullable Integer tokensUsed
    );
    
    /**
     * Retrieves a cached result by content hash.
     *
     * @param projectId the project ID (required)
     * @param cacheType the type of cached result (required)
     * @param contentHash SHA-256 hash of input content (required)
     * @return CompletableFuture with the cache entry if found
     */
    CompletableFuture<Optional<ExtractionCache>> get(
        @NotNull String projectId,
        @NotNull CacheType cacheType,
        @NotNull String contentHash
    );
    
    /**
     * Gets all extraction results for a chunk (for rebuild).
     *
     * @param projectId the project ID (required)
     * @param chunkId the chunk ID (required)
     * @return CompletableFuture with list of cache entries
     */
    CompletableFuture<List<ExtractionCache>> getByChunkId(
        @NotNull String projectId,
        @NotNull String chunkId
    );
    
    /**
     * Deletes all cache entries for a project.
     *
     * @param projectId the project ID (required)
     * @return CompletableFuture with count of deleted entries
     */
    CompletableFuture<Integer> deleteByProject(@NotNull String projectId);
    
    /**
     * Closes resources.
     */
    @Override
    void close();
}
