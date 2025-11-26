package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for selecting relevant chunks for a query.
 * 
 * <p>Abstracts the chunk selection strategy to allow different approaches:</p>
 * <ul>
 *   <li>{@link VectorChunkSelector} - Traditional vector similarity search</li>
 *   <li>{@link WeightedChunkSelector} - Weighted polling based on entity/relation connections</li>
 * </ul>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * ChunkSelector selector = factory.getSelector(config);
 * List<ScoredChunk> chunks = selector.selectChunks(
 *     queryEmbedding,
 *     projectId,
 *     10,  // topK
 *     context
 * ).join();
 * }</pre>
 * 
 * @since spec-007
 */
public interface ChunkSelector {
    
    /**
     * Selects the most relevant chunks for a query.
     * 
     * @param queryEmbedding The query embedding vector
     * @param projectId The project ID for isolation
     * @param topK Maximum number of chunks to return
     * @param context Additional context for selection (can be null)
     * @return CompletableFuture with list of scored chunks, ordered by relevance
     */
    CompletableFuture<List<ScoredChunk>> selectChunks(
        @NotNull Object queryEmbedding,
        @NotNull String projectId,
        int topK,
        SelectionContext context
    );
    
    /**
     * Gets the name of this selector strategy.
     * 
     * @return Strategy name (e.g., "vector", "weighted")
     */
    String getStrategyName();
    
    /**
     * A chunk with its relevance score.
     * 
     * @param chunkId The chunk ID
     * @param content The chunk content
     * @param score The relevance score (higher = more relevant)
     * @param documentId The source document ID
     * @param chunkIndex The index within the document
     */
    record ScoredChunk(
        @NotNull String chunkId,
        @NotNull String content,
        double score,
        String documentId,
        int chunkIndex
    ) {
        /**
         * Creates a ScoredChunk from a VectorSearchResult.
         */
        public static ScoredChunk fromVectorResult(VectorStorage.VectorSearchResult result) {
            return new ScoredChunk(
                result.id(),
                result.metadata().content() != null ? result.metadata().content() : "",
                result.score(),
                result.metadata().documentId(),
                result.metadata().chunkIndex() != null ? result.metadata().chunkIndex() : 0
            );
        }
    }
    
    /**
     * Additional context for chunk selection.
     * 
     * <p>Provides information that may be useful for weighted selection,
     * such as entities found in the query or related relations.</p>
     * 
     * @param queryText The original query text
     * @param entityNames Entity names relevant to the query
     * @param relationKeywords Keywords from relevant relations
     * @param entityChunkWeights Map of entity name to chunk weight boost
     */
    record SelectionContext(
        String queryText,
        List<String> entityNames,
        List<String> relationKeywords,
        java.util.Map<String, Double> entityChunkWeights
    ) {
        /**
         * Creates an empty context.
         */
        public static SelectionContext empty() {
            return new SelectionContext(null, List.of(), List.of(), java.util.Map.of());
        }
        
        /**
         * Creates a context with just the query text.
         */
        public static SelectionContext withQuery(String queryText) {
            return new SelectionContext(queryText, List.of(), List.of(), java.util.Map.of());
        }
    }
}
