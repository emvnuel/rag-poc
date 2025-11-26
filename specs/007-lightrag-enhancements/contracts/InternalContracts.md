# Internal Contracts: LightRAG Official Implementation Enhancements

**Feature**: 007-lightrag-enhancements  
**Date**: 2025-11-26  
**Purpose**: Define internal interface contracts for new components

## Service Interfaces

### 1. Reranker Interface

```java
/**
 * Reranks retrieved chunks based on query relevance.
 * 
 * Contract:
 * - MUST return chunks in descending relevance order
 * - MUST filter chunks below minScore threshold
 * - MUST fall back to original order on provider failure (circuit breaker)
 * - Timeout: 2000ms (configurable)
 */
public interface Reranker {
    
    /**
     * Reranks chunks by relevance to query.
     *
     * @param query The user's query string (required)
     * @param chunks Chunks to rerank (required, non-empty)
     * @param topK Maximum number of chunks to return
     * @return Reranked chunks with scores, or original order on failure
     */
    List<RerankedChunk> rerank(
        @NotNull String query,
        @NotNull List<Chunk> chunks,
        int topK
    );
    
    /**
     * Checks if the reranker provider is available.
     */
    boolean isAvailable();
    
    /**
     * Gets the provider name (cohere, jina, none).
     */
    String getProviderName();
}
```

### 2. DocumentDeletionService Interface

```java
/**
 * Handles document deletion with intelligent KG rebuild.
 * 
 * Contract:
 * - MUST identify entities/relations sourced from deleted document
 * - MUST completely remove entities with no remaining sources
 * - MUST rebuild descriptions for entities with partial sources
 * - MUST use cached extraction results (no LLM re-calls for cached content)
 * - MUST clean up vector embeddings
 * - MUST be transactional (all-or-nothing)
 */
public interface DocumentDeletionService {
    
    /**
     * Deletes a document and rebuilds affected knowledge graph components.
     *
     * @param projectId Project containing the document (required)
     * @param documentId Document to delete (required)
     * @param skipRebuild Skip KG rebuild (faster, may leave stale entities)
     * @return Detailed result of deletion and rebuild operations
     * @throws IllegalArgumentException if document not found in project
     */
    KnowledgeRebuildResult deleteDocument(
        @NotNull UUID projectId,
        @NotNull UUID documentId,
        boolean skipRebuild
    );
}
```

### 3. EntityMergeService Interface

```java
/**
 * Handles entity merge operations with relationship management.
 * 
 * Contract:
 * - MUST validate all source entities exist
 * - MUST redirect all relationships to target entity
 * - MUST prevent self-loop relationships after merge
 * - MUST deduplicate relationships (same src->tgt pair)
 * - MUST apply configured description merge strategy
 * - MUST delete source entities and embeddings after merge
 * - MUST be transactional (all-or-nothing)
 */
public interface EntityMergeService {
    
    /**
     * Merges multiple entities into a single target entity.
     *
     * @param projectId Project containing the entities (required)
     * @param sourceEntities Entity names to merge (required, non-empty)
     * @param targetEntity Target entity name (required)
     * @param strategy Description merge strategy (default: CONCATENATE)
     * @param targetEntityData Optional data to set on target entity
     * @return Detailed result of merge operation
     * @throws IllegalArgumentException if source entity not found
     * @throws IllegalStateException if circular merge detected
     */
    MergeResult mergeEntities(
        @NotNull UUID projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity,
        @NotNull MergeStrategy strategy,
        @Nullable Map<String, Object> targetEntityData
    );
}
```

### 4. GraphExporter Interface

```java
/**
 * Exports knowledge graph to various formats.
 * 
 * Contract:
 * - MUST support streaming for large graphs (50k+ entities)
 * - MUST use batch fetching to avoid memory exhaustion
 * - MUST write entities and relations to output
 * - SHOULD include metadata (export date, counts) in output
 */
public interface GraphExporter {
    
    /**
     * Exports knowledge graph to output stream.
     *
     * @param projectId Project to export (required)
     * @param output Target output stream (required)
     * @param config Export configuration
     */
    void export(
        @NotNull UUID projectId,
        @NotNull OutputStream output,
        @NotNull ExportConfig config
    );
    
    /**
     * Gets the format this exporter handles (csv, xlsx, md, txt).
     */
    String getFormat();
    
    /**
     * Gets the content type for HTTP response.
     */
    String getContentType();
}
```

### 5. TokenTracker Interface

```java
/**
 * Tracks token usage across LLM operations within a request.
 * 
 * Contract:
 * - MUST be request-scoped (isolated per HTTP request)
 * - MUST be thread-safe (parallel LLM calls)
 * - MUST track input and output tokens separately
 * - MUST track by operation type for debugging
 */
@RequestScoped
public interface TokenTracker {
    
    /**
     * Records token usage for an operation.
     *
     * @param usage Token usage record
     */
    void track(@NotNull TokenUsage usage);
    
    /**
     * Gets aggregated token summary.
     *
     * @return Summary with totals and breakdown by operation type
     */
    TokenSummary getSummary();
    
    /**
     * Resets all counters (for testing or session boundaries).
     */
    void reset();
    
    /**
     * Gets raw usage records (for detailed logging).
     */
    List<TokenUsage> getUsages();
}
```

### 6. ChunkSelector Interface

```java
/**
 * Selects chunks from entities based on configurable strategy.
 * 
 * Contract:
 * - MUST respect maxChunks limit
 * - VECTOR strategy: Select by embedding similarity to query
 * - WEIGHT strategy: Select by occurrence count across entities
 */
public interface ChunkSelector {
    
    /**
     * Selects chunks from entities.
     *
     * @param query The user's query (for VECTOR strategy)
     * @param entities Entities containing source chunk IDs
     * @param maxChunks Maximum chunks to return
     * @return Selected chunks
     */
    List<Chunk> selectChunks(
        @NotNull String query,
        @NotNull List<Entity> entities,
        int maxChunks
    );
    
    /**
     * Gets the strategy name (vector, weight).
     */
    String getStrategy();
}
```

### 7. DescriptionSummarizer Interface (Enhanced)

```java
/**
 * Summarizes entity descriptions using map-reduce pattern.
 * 
 * Contract (enhanced from spec-006):
 * - MUST use map-reduce for very long description lists
 * - MUST skip summarization if below thresholds
 * - MUST recursively summarize if single pass exceeds token limit
 */
public interface DescriptionSummarizer {
    
    /**
     * Summarizes multiple descriptions into a coherent single description.
     *
     * @param entityName The entity being summarized
     * @param descriptions List of accumulated descriptions
     * @return Summarized description
     */
    String summarize(@NotNull String entityName, @NotNull List<String> descriptions);
    
    /**
     * Checks if summarization is needed based on thresholds.
     * 
     * @param descriptions Descriptions to evaluate
     * @return true if LLM summarization should be used
     */
    boolean needsSummarization(@NotNull List<String> descriptions);
    
    /**
     * Uses map-reduce for very large description sets.
     *
     * @param entityName The entity being summarized
     * @param descriptions List of descriptions (may be very long)
     * @return Summarized description via map-reduce
     */
    String mapReduceSummarize(@NotNull String entityName, @NotNull List<String> descriptions);
}
```

## Record Types

### RerankedChunk

```java
/**
 * A chunk with reranker relevance score.
 */
public record RerankedChunk(
    @NotNull Chunk chunk,
    double relevanceScore,  // 0.0 - 1.0
    int originalRank,       // Position before reranking
    int newRank             // Position after reranking
) {
    public RerankedChunk {
        Objects.requireNonNull(chunk);
        if (relevanceScore < 0.0 || relevanceScore > 1.0) {
            throw new IllegalArgumentException("relevanceScore must be 0.0-1.0");
        }
    }
}
```

### TokenUsage

```java
/**
 * Token consumption for a single LLM operation.
 */
public record TokenUsage(
    @NotNull String operationType,  // INGESTION, QUERY, SUMMARIZATION, KEYWORD_EXTRACTION, RERANK
    @NotNull String modelName,
    int inputTokens,
    int outputTokens,
    @NotNull Instant timestamp
) {
    public TokenUsage {
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(modelName);
        Objects.requireNonNull(timestamp);
        if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must be >= 0");
        if (outputTokens < 0) throw new IllegalArgumentException("outputTokens must be >= 0");
    }
    
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
```

### TokenSummary

```java
/**
 * Aggregated token usage for a session/request.
 */
public record TokenSummary(
    int totalInputTokens,
    int totalOutputTokens,
    @NotNull Map<String, Integer> byOperationType
) {
    public TokenSummary {
        Objects.requireNonNull(byOperationType);
    }
    
    public int totalTokens() {
        return totalInputTokens + totalOutputTokens;
    }
}
```

### ExportConfig

```java
/**
 * Configuration for knowledge graph export.
 */
public record ExportConfig(
    boolean includeVectors,
    boolean includeChunks,
    int batchSize
) {
    public ExportConfig {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
    }
    
    public static ExportConfig defaults() {
        return new ExportConfig(false, false, 1000);
    }
}
```

## GraphStorage Extensions

```java
/**
 * New methods to add to GraphStorage interface for deletion/export support.
 */

// For document deletion - find affected entities
CompletableFuture<Set<String>> getEntitiesBySourceChunks(
    @NotNull UUID projectId,
    @NotNull List<UUID> chunkIds
);

// For document deletion - find affected relations
CompletableFuture<Set<String>> getRelationsBySourceChunks(
    @NotNull UUID projectId,
    @NotNull List<UUID> chunkIds
);

// For document deletion - batch delete entities
CompletableFuture<Void> deleteEntities(
    @NotNull UUID projectId,
    @NotNull Set<String> entityNames
);

// For document deletion - batch delete relations
CompletableFuture<Void> deleteRelations(
    @NotNull UUID projectId,
    @NotNull Set<String> relationKeys  // "source->target" format
);

// For document deletion - update entity description
CompletableFuture<Void> updateEntityDescription(
    @NotNull UUID projectId,
    @NotNull String entityName,
    @NotNull String description,
    @NotNull Set<UUID> sourceIds
);

// For export - paginated entity fetch
CompletableFuture<List<Entity>> getEntitiesBatch(
    @NotNull UUID projectId,
    int offset,
    int limit
);

// For export - paginated relation fetch
CompletableFuture<List<Relation>> getRelationsBatch(
    @NotNull UUID projectId,
    int offset,
    int limit
);

// For entity merge - get all relations for entity
CompletableFuture<List<Relation>> getRelationsForEntity(
    @NotNull UUID projectId,
    @NotNull String entityName
);
```

## VectorStorage Extensions

```java
/**
 * New methods to add to VectorStorage interface for deletion support.
 */

// For document deletion - batch delete entity embeddings
CompletableFuture<Void> deleteEntityEmbeddings(
    @NotNull UUID projectId,
    @NotNull Set<String> entityNames
);

// For document deletion - batch delete chunk embeddings
CompletableFuture<Void> deleteChunkEmbeddings(
    @NotNull UUID projectId,
    @NotNull Set<UUID> chunkIds
);
```

## Error Handling

All interfaces follow these error handling conventions:

| Error Type | Handling |
|------------|----------|
| `IllegalArgumentException` | Invalid input (null, empty, invalid format) |
| `IllegalStateException` | System not initialized, circular merge, conflict |
| `RerankerException` | Reranker API failures (falls back to original order) |
| `ExportException` | Export failures (I/O, format issues) |
| `TransactionException` | Database transaction failures (rollback) |

## Threading Model

| Interface | Threading | Notes |
|-----------|-----------|-------|
| Reranker | Async-capable | May make external API calls |
| DocumentDeletionService | Synchronous | Single transaction |
| EntityMergeService | Synchronous | Single transaction |
| GraphExporter | Synchronous | Streaming I/O |
| TokenTracker | Thread-safe | Request-scoped, CopyOnWriteArrayList |
| ChunkSelector | Async-capable | May query vector store |
| DescriptionSummarizer | Async-capable | LLM calls |

## Configuration Properties

```properties
# Reranker
lightrag.rerank.enabled=true
lightrag.rerank.provider=cohere
lightrag.rerank.cohere.api-key=${COHERE_API_KEY}
lightrag.rerank.cohere.model=rerank-english-v3.0
lightrag.rerank.jina.api-key=${JINA_API_KEY}
lightrag.rerank.jina.model=jina-reranker-v2-base-multilingual
lightrag.rerank.min-score=0.1
lightrag.rerank.fallback-timeout-ms=2000

# Description Summarization (map-reduce)
lightrag.description.force-summary-count=6
lightrag.description.summary-context-size=10000
lightrag.description.summary-max-tokens=500
lightrag.description.max-map-iterations=3

# Chunk Selection
lightrag.query.chunk-selection-method=vector
lightrag.query.max-related-chunks=20

# Export
lightrag.export.batch-size=1000
```
