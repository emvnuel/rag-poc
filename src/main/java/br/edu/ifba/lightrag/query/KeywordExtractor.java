package br.edu.ifba.lightrag.query;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for extracting keywords from user queries.
 * 
 * <p>Implementations analyze query text to extract both high-level (thematic)
 * and low-level (entity) keywords for smarter retrieval routing.</p>
 * 
 * <p>High-level keywords are used for global/relation-based retrieval,
 * focusing on themes, concepts, and relationships. Low-level keywords
 * are used for local/entity-based retrieval, focusing on specific entities.</p>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * KeywordExtractor extractor = new LLMKeywordExtractor(llmFunction, config);
 * KeywordResult result = extractor.extract("What are the main AI research trends?", projectId).join();
 * 
 * // Use high-level keywords for global search
 * List<String> themes = result.highLevelKeywords(); // ["AI research", "technology trends"]
 * 
 * // Use low-level keywords for local search
 * List<String> entities = result.lowLevelKeywords(); // ["artificial intelligence", "machine learning"]
 * }</pre>
 * 
 * @see KeywordResult
 * @see LLMKeywordExtractor
 */
public interface KeywordExtractor {
    
    /**
     * Extracts high-level and low-level keywords from a query.
     * 
     * <p>This method may use LLM for extraction and may cache results
     * for identical queries within the same project.</p>
     * 
     * @param query the user query to extract keywords from
     * @param projectId the project context for caching (may be null for no caching)
     * @return CompletableFuture containing the extracted keywords
     */
    CompletableFuture<KeywordResult> extract(String query, String projectId);
    
    /**
     * Retrieves cached keywords for a query if available.
     * 
     * <p>Returns null if no cached result exists or if caching is disabled.</p>
     * 
     * @param queryHash SHA-256 hash of the query
     * @param projectId the project context
     * @return cached KeywordResult or null if not found
     */
    KeywordResult getCached(String queryHash, String projectId);
    
    /**
     * Computes a hash for the query for caching purposes.
     * 
     * <p>Default implementation uses SHA-256.</p>
     * 
     * @param query the query to hash
     * @return hex-encoded hash string
     */
    default String hashQuery(String query) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
