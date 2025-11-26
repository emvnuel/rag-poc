# Internal Contracts: LightRAG Official Implementation Alignment

**Feature**: 006-lightrag-official-impl  
**Date**: 2025-11-25  
**Purpose**: Define internal interface contracts for new components

## Note on External API

This feature does **NOT** add new REST endpoints. All improvements are internal to the LightRAG processing pipeline. The existing Chat API (`/api/chat`) continues to work unchanged, but with improved quality and caching.

## Internal Interface Contracts

### 1. KeywordExtractor Interface

```java
/**
 * Extracts high-level and low-level keywords from queries for context routing.
 * 
 * Contract:
 * - MUST return non-null KeywordResult
 * - SHOULD cache results by query hash
 * - HIGH_LEVEL keywords route to relationship search
 * - LOW_LEVEL keywords route to entity search
 */
public interface KeywordExtractor {
    
    /**
     * Extracts keywords from a user query.
     *
     * @param query The user's query string (required)
     * @param projectId The project context (required)
     * @return KeywordResult containing high and low level keywords
     */
    CompletableFuture<KeywordResult> extract(
        @NotNull String query,
        @NotNull String projectId
    );
    
    /**
     * Checks if a cached result exists for this query.
     */
    Optional<KeywordResult> getCached(@NotNull String queryHash);
}
```

### 2. DescriptionSummarizer Interface

```java
/**
 * Summarizes accumulated entity descriptions using LLM.
 * 
 * Contract:
 * - MUST be invoked when descriptions exceed token threshold
 * - SHOULD use map-reduce for very long description lists
 * - Result MUST be stored in ExtractionCache
 */
public interface DescriptionSummarizer {
    
    /**
     * Summarizes multiple descriptions into a coherent single description.
     *
     * @param entityName The entity being summarized
     * @param descriptions List of accumulated descriptions
     * @param projectId The project context
     * @return Summarized description
     */
    CompletableFuture<String> summarize(
        @NotNull String entityName,
        @NotNull List<String> descriptions,
        @NotNull String projectId
    );
    
    /**
     * Checks if summarization is needed based on token count.
     */
    boolean needsSummarization(@NotNull List<String> descriptions);
}
```

### 3. ExtractionCacheStorage Interface

```java
/**
 * Stores and retrieves LLM extraction results for rebuild capability.
 * 
 * Contract:
 * - MUST cascade delete on project deletion
 * - MUST maintain unique constraint on (projectId, cacheType, contentHash)
 * - chunk_id FK SHOULD SET NULL on chunk deletion (orphan is OK)
 */
public interface ExtractionCacheStorage extends AutoCloseable {
    
    /**
     * Stores an extraction result.
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
     */
    CompletableFuture<Optional<ExtractionCache>> get(
        @NotNull String projectId,
        @NotNull CacheType cacheType,
        @NotNull String contentHash
    );
    
    /**
     * Gets all extraction results for a chunk (for rebuild).
     */
    CompletableFuture<List<ExtractionCache>> getByChunkId(
        @NotNull String projectId,
        @NotNull String chunkId
    );
    
    /**
     * Deletes all cache entries for a project.
     */
    CompletableFuture<Integer> deleteByProject(@NotNull String projectId);
}
```

### 4. ContextMerger Interface

```java
/**
 * Merges context from multiple sources using round-robin interleaving.
 * 
 * Contract:
 * - MUST interleave from sources in round-robin fashion
 * - MUST respect token budget
 * - MUST stop when budget exhausted
 */
public interface ContextMerger {
    
    /**
     * Merges context items from multiple sources.
     *
     * @param sources List of context item lists (entities, relations, chunks)
     * @param maxTokens Maximum token budget
     * @return Merged context string within token budget
     */
    String merge(
        @NotNull List<List<String>> sources,
        int maxTokens
    );
    
    /**
     * Merges with metadata tracking for citations.
     */
    MergeResult mergeWithMetadata(
        @NotNull List<List<ContextItem>> sources,
        int maxTokens
    );
}
```

### 5. GraphStorage Batch Methods (Extension)

```java
/**
 * Batch operation extensions to GraphStorage interface.
 * 
 * Contract:
 * - MUST process in configurable batch sizes
 * - MUST use IN clause for efficient queries
 * - MUST return partial results if some entities not found
 */

// Add to GraphStorage interface:

/**
 * Gets multiple entities by name in batch.
 *
 * @param projectId The project graph to query
 * @param entityNames List of entity names to fetch
 * @param batchSize Number of entities per query (default 500)
 * @return Map of entityName -> Entity (missing entities not in map)
 */
CompletableFuture<Map<String, Entity>> getEntitiesBatch(
    @NotNull String projectId,
    @NotNull List<String> entityNames,
    int batchSize
);

/**
 * Gets degree (edge count) for multiple nodes in batch.
 *
 * @param projectId The project graph to query
 * @param entityNames List of entity names
 * @param batchSize Number of entities per query (default 500)
 * @return Map of entityName -> degree
 */
CompletableFuture<Map<String, Integer>> getNodeDegreesBatch(
    @NotNull String projectId,
    @NotNull List<String> entityNames,
    int batchSize
);
```

## Record Types

### KeywordResult

```java
public record KeywordResult(
    @NotNull List<String> highLevelKeywords,
    @NotNull List<String> lowLevelKeywords,
    @NotNull String queryHash,
    @Nullable Instant cachedAt
) {
    public KeywordResult {
        Objects.requireNonNull(highLevelKeywords);
        Objects.requireNonNull(lowLevelKeywords);
        Objects.requireNonNull(queryHash);
    }
    
    public boolean isEmpty() {
        return highLevelKeywords.isEmpty() && lowLevelKeywords.isEmpty();
    }
}
```

### ContextItem

```java
public record ContextItem(
    @NotNull String content,
    @NotNull String type,  // "entity", "relation", "chunk"
    @Nullable String sourceId,
    @Nullable String filePath,
    int tokens
) {}
```

### MergeResult

```java
public record MergeResult(
    @NotNull String mergedContext,
    @NotNull List<ContextItem> includedItems,
    int totalTokens,
    int itemsIncluded,
    int itemsTruncated
) {}
```

## Error Handling

All interfaces follow these error handling conventions:

| Error Type | Handling |
|------------|----------|
| `IllegalArgumentException` | Invalid input (null, empty, invalid format) |
| `IllegalStateException` | System not initialized or misconfigured |
| `CompletionException` | Wraps underlying exceptions from async operations |
| `LLMException` (new) | LLM API failures (timeout, rate limit, invalid response) |

## Threading Model

All interfaces return `CompletableFuture<T>` and are designed for:
- Virtual thread execution (Java 21)
- Non-blocking I/O
- Configurable executor pools
