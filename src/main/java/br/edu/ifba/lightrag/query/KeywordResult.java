package br.edu.ifba.lightrag.query;

import java.time.Instant;
import java.util.List;

/**
 * Result of keyword extraction from a query.
 * 
 * <p>Contains both high-level (thematic) and low-level (entity) keywords
 * extracted from a user query for smarter retrieval routing.</p>
 * 
 * <p>High-level keywords are used for global/relation-based retrieval,
 * while low-level keywords are used for local/entity-based retrieval.</p>
 * 
 * @param highLevelKeywords thematic keywords for relation/global search (e.g., "artificial intelligence", "machine learning trends")
 * @param lowLevelKeywords entity-specific keywords for entity/local search (e.g., "OpenAI", "GPT-4", "neural network")
 * @param queryHash hash of the original query for caching
 * @param cachedAt timestamp when this result was cached (null if fresh)
 */
public record KeywordResult(
    List<String> highLevelKeywords,
    List<String> lowLevelKeywords,
    String queryHash,
    Instant cachedAt
) {
    /**
     * Creates a fresh (non-cached) keyword result.
     * 
     * @param highLevelKeywords thematic keywords
     * @param lowLevelKeywords entity keywords
     * @param queryHash hash of the query
     * @return new KeywordResult without cache timestamp
     */
    public static KeywordResult fresh(List<String> highLevelKeywords, List<String> lowLevelKeywords, String queryHash) {
        return new KeywordResult(highLevelKeywords, lowLevelKeywords, queryHash, null);
    }
    
    /**
     * Creates a cached keyword result with current timestamp.
     * 
     * @param highLevelKeywords thematic keywords
     * @param lowLevelKeywords entity keywords
     * @param queryHash hash of the query
     * @return new KeywordResult with current timestamp
     */
    public static KeywordResult cached(List<String> highLevelKeywords, List<String> lowLevelKeywords, String queryHash) {
        return new KeywordResult(highLevelKeywords, lowLevelKeywords, queryHash, Instant.now());
    }
    
    /**
     * Creates an empty keyword result (extraction failed or returned nothing).
     * 
     * @param queryHash hash of the query
     * @return empty KeywordResult
     */
    public static KeywordResult empty(String queryHash) {
        return new KeywordResult(List.of(), List.of(), queryHash, null);
    }
    
    /**
     * Checks if this result is from cache.
     * 
     * @return true if cachedAt is not null
     */
    public boolean isCached() {
        return cachedAt != null;
    }
    
    /**
     * Checks if this result has any keywords.
     * 
     * @return true if either high or low level keywords are present
     */
    public boolean hasKeywords() {
        return !highLevelKeywords.isEmpty() || !lowLevelKeywords.isEmpty();
    }
    
    /**
     * Gets total keyword count.
     * 
     * @return sum of high and low level keyword counts
     */
    public int totalKeywordCount() {
        return highLevelKeywords.size() + lowLevelKeywords.size();
    }
    
    /**
     * Checks if this result has no keywords.
     * 
     * @return true if both high and low level keyword lists are empty
     */
    public boolean isEmpty() {
        return highLevelKeywords.isEmpty() && lowLevelKeywords.isEmpty();
    }
}
