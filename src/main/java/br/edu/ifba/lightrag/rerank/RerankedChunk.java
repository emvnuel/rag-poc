package br.edu.ifba.lightrag.rerank;

import br.edu.ifba.lightrag.core.Chunk;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A chunk with its reranker relevance score.
 * 
 * <p>Represents the result of reranking a chunk, containing the original chunk,
 * its relevance score from the reranker, and rank information before and after reranking.
 *
 * @param chunk         the original chunk (required)
 * @param relevanceScore the reranker score (0.0 - 1.0)
 * @param originalRank  position before reranking (0-based)
 * @param newRank       position after reranking (0-based)
 */
public record RerankedChunk(
    @NotNull Chunk chunk,
    double relevanceScore,
    int originalRank,
    int newRank
) {
    
    /**
     * Constructs a new RerankedChunk with validation.
     *
     * @param chunk         the original chunk (required)
     * @param relevanceScore the reranker score (must be between 0.0 and 1.0)
     * @param originalRank  position before reranking (must be >= 0)
     * @param newRank       position after reranking (must be >= 0)
     * @throws NullPointerException if chunk is null
     * @throws IllegalArgumentException if relevanceScore is not in [0.0, 1.0] or ranks are negative
     */
    public RerankedChunk {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (relevanceScore < 0.0 || relevanceScore > 1.0) {
            throw new IllegalArgumentException("relevanceScore must be between 0.0 and 1.0, got: " + relevanceScore);
        }
        if (originalRank < 0) {
            throw new IllegalArgumentException("originalRank must be >= 0, got: " + originalRank);
        }
        if (newRank < 0) {
            throw new IllegalArgumentException("newRank must be >= 0, got: " + newRank);
        }
    }
    
    /**
     * Creates a RerankedChunk with the same relevance as original rank (for no-op reranking).
     *
     * @param chunk the chunk
     * @param rank  the rank (used for both original and new rank)
     * @param score the relevance score
     * @return a new RerankedChunk
     */
    public static RerankedChunk passthrough(@NotNull Chunk chunk, int rank, double score) {
        return new RerankedChunk(chunk, score, rank, rank);
    }
    
    /**
     * Creates a RerankedChunk for a chunk that has been reranked.
     *
     * @param chunk        the chunk
     * @param originalRank position before reranking
     * @param newRank      position after reranking
     * @param score        the relevance score from the reranker
     * @return a new RerankedChunk
     */
    public static RerankedChunk reranked(@NotNull Chunk chunk, int originalRank, int newRank, double score) {
        return new RerankedChunk(chunk, score, originalRank, newRank);
    }
    
    /**
     * Returns the rank change (positive = moved up, negative = moved down).
     *
     * @return the difference between original and new rank
     */
    public int rankChange() {
        return originalRank - newRank;
    }
    
    /**
     * Returns true if the chunk moved up in ranking.
     *
     * @return true if newRank < originalRank
     */
    public boolean movedUp() {
        return newRank < originalRank;
    }
    
    /**
     * Returns true if the chunk moved down in ranking.
     *
     * @return true if newRank > originalRank
     */
    public boolean movedDown() {
        return newRank > originalRank;
    }
}
