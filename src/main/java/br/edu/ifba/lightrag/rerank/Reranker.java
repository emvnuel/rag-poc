package br.edu.ifba.lightrag.rerank;

import br.edu.ifba.lightrag.core.Chunk;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Reranks retrieved chunks based on query relevance.
 * 
 * <p>Contract:
 * <ul>
 *   <li>MUST return chunks in descending relevance order</li>
 *   <li>MUST filter chunks below minScore threshold (from config)</li>
 *   <li>MUST fall back to original order on provider failure (circuit breaker)</li>
 *   <li>Default timeout: 2000ms (configurable)</li>
 * </ul>
 * 
 * <p>Implementations include:
 * <ul>
 *   <li>{@code NoOpReranker} - passthrough that maintains original order</li>
 *   <li>{@code CohereReranker} - uses Cohere Rerank API</li>
 *   <li>{@code JinaReranker} - uses Jina Rerank API</li>
 * </ul>
 */
public interface Reranker {
    
    /**
     * Reranks chunks by relevance to query.
     * 
     * <p>The returned list:
     * <ul>
     *   <li>Is sorted by relevance score in descending order</li>
     *   <li>Contains at most {@code topK} chunks</li>
     *   <li>Excludes chunks below the configured minimum score threshold</li>
     *   <li>Falls back to original order on timeout or error</li>
     * </ul>
     *
     * @param query  the user's query string (required)
     * @param chunks chunks to rerank (required, non-empty)
     * @param topK   maximum number of chunks to return
     * @return reranked chunks with scores, or original order on failure
     * @throws NullPointerException if query or chunks is null
     * @throws IllegalArgumentException if chunks is empty or topK <= 0
     */
    @NotNull
    List<RerankedChunk> rerank(
        @NotNull String query,
        @NotNull List<Chunk> chunks,
        int topK
    );
    
    /**
     * Checks if the reranker provider is available.
     * 
     * <p>An unavailable provider may be due to:
     * <ul>
     *   <li>Missing API key configuration</li>
     *   <li>Circuit breaker open (too many recent failures)</li>
     *   <li>Provider disabled in configuration</li>
     * </ul>
     *
     * @return true if the reranker can accept requests
     */
    boolean isAvailable();
    
    /**
     * Gets the provider name.
     *
     * @return provider identifier (e.g., "cohere", "jina", "none")
     */
    @NotNull
    String getProviderName();
}
