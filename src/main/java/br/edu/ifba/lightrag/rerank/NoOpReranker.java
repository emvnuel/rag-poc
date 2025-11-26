package br.edu.ifba.lightrag.rerank;

import br.edu.ifba.lightrag.core.Chunk;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A no-op reranker that maintains the original chunk order.
 * 
 * <p>This implementation is used when:
 * <ul>
 *   <li>Reranking is disabled in configuration</li>
 *   <li>No API key is configured for the selected provider</li>
 *   <li>As a fallback when the actual reranker fails (circuit breaker open)</li>
 * </ul>
 * 
 * <p>Chunks are assigned synthetic relevance scores based on their position,
 * with the first chunk receiving score 1.0 and subsequent chunks receiving
 * decreasing scores (0.9, 0.8, etc.).
 */
@ApplicationScoped
@Named("noOpReranker")
public class NoOpReranker implements Reranker {
    
    private static final Logger logger = Logger.getLogger(NoOpReranker.class);
    private static final String PROVIDER_NAME = "none";
    
    /**
     * Returns chunks in their original order with synthetic relevance scores.
     * 
     * <p>Scores are assigned as: 1.0 - (rank * 0.05), clamped to minimum 0.1
     *
     * @param query  the user's query (not used)
     * @param chunks chunks to return
     * @param topK   maximum number of chunks to return
     * @return chunks in original order with position-based scores
     */
    @Override
    @NotNull
    public List<RerankedChunk> rerank(
        @NotNull String query,
        @NotNull List<Chunk> chunks,
        int topK
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        
        logger.debugf("NoOpReranker: returning %d chunks (of %d) in original order",
            Math.min(topK, chunks.size()), chunks.size());
        
        List<RerankedChunk> results = new ArrayList<>();
        int limit = Math.min(topK, chunks.size());
        
        for (int i = 0; i < limit; i++) {
            // Assign synthetic scores: 1.0, 0.95, 0.90, ..., minimum 0.1
            double score = Math.max(0.1, 1.0 - (i * 0.05));
            results.add(RerankedChunk.passthrough(chunks.get(i), i, score));
        }
        
        return results;
    }
    
    /**
     * Always returns true since no external service is required.
     *
     * @return true
     */
    @Override
    public boolean isAvailable() {
        return true;
    }
    
    /**
     * Returns "none" as the provider name.
     *
     * @return "none"
     */
    @Override
    @NotNull
    public String getProviderName() {
        return PROVIDER_NAME;
    }
}
