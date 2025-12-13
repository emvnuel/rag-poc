package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Service for caching query responses to avoid duplicate LLM calls.
 * 
 * <p>Ported from official LightRAG Python's LIGHTRAG_LLM_CACHE functionality.
 * Caches query responses based on query text, mode, and parameters.</p>
 * 
 * <h2>Cache Key Computation:</h2>
 * <p>The cache key is computed from:</p>
 * <ul>
 *   <li>Project ID - ensures isolation between projects</li>
 *   <li>Query text - the user's question</li>
 *   <li>Query mode - LOCAL, GLOBAL, HYBRID, etc.</li>
 *   <li>Top-K parameter - number of results requested</li>
 * </ul>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * // Check cache before executing query
 * Optional<CachedQueryResult> cached = cacheService.get(projectId, query, param).join();
 * if (cached.isPresent()) {
 *     return cached.get().toQueryResult();
 * }
 * 
 * // Execute query and cache result
 * LightRAGQueryResult result = executeQuery(query, param).join();
 * cacheService.store(projectId, query, param, result).join();
 * }</pre>
 * 
 * @since spec-008 (query caching enhancement)
 */
@ApplicationScoped
public class QueryCacheService {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryCacheService.class);
    
    private final ExtractionCacheStorage cacheStorage;
    
    /**
     * Default constructor for CDI proxy.
     */
    public QueryCacheService() {
        this.cacheStorage = null;
    }
    
    /**
     * Creates a QueryCacheService with the given cache storage.
     * 
     * @param cacheStorage the extraction cache storage to use
     */
    @Inject
    public QueryCacheService(ExtractionCacheStorage cacheStorage) {
        this.cacheStorage = cacheStorage;
    }
    
    /**
     * Retrieves a cached query result if available.
     * 
     * @param projectId the project ID
     * @param query the query text
     * @param param the query parameters
     * @return CompletableFuture with optional cached result
     */
    public CompletableFuture<Optional<CachedQueryResult>> get(
        @NotNull String projectId,
        @NotNull String query,
        @NotNull QueryParam param
    ) {
        if (cacheStorage == null) {
            logger.debug("Cache storage not available, cache miss");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        String contentHash = computeCacheKey(query, param);
        
        return cacheStorage.get(projectId, CacheType.QUERY_RESPONSE, contentHash)
            .<Optional<CachedQueryResult>>thenApply(optionalCache -> {
                if (optionalCache.isPresent()) {
                    logger.debug("Query cache HIT for project={}, mode={}, hash={}", 
                        projectId, param.getMode(), contentHash.substring(0, 8));
                    
                    String cachedJson = optionalCache.get().result();
                    return Optional.of(CachedQueryResult.fromJson(cachedJson, param.getMode()));
                }
                
                logger.debug("Query cache MISS for project={}, mode={}, hash={}", 
                    projectId, param.getMode(), contentHash.substring(0, 8));
                return Optional.empty();
            })
            .exceptionally(e -> {
                logger.warn("Failed to retrieve from query cache: {}", e.getMessage());
                return Optional.empty();
            });
    }
    
    /**
     * Stores a query result in the cache.
     * 
     * @param projectId the project ID
     * @param query the query text
     * @param param the query parameters
     * @param result the query result to cache
     * @return CompletableFuture that completes when stored
     */
    public CompletableFuture<Void> store(
        @NotNull String projectId,
        @NotNull String query,
        @NotNull QueryParam param,
        @NotNull LightRAGQueryResult result
    ) {
        if (cacheStorage == null) {
            logger.debug("Cache storage not available, skipping store");
            return CompletableFuture.completedFuture(null);
        }
        
        String contentHash = computeCacheKey(query, param);
        String resultJson = CachedQueryResult.toJson(result);
        
        return cacheStorage.store(
            projectId,
            CacheType.QUERY_RESPONSE,
            null,  // No specific chunk ID for query results
            contentHash,
            resultJson,
            null   // Token count not tracked for query responses
        ).thenAccept(cacheId -> {
            logger.debug("Stored query result in cache: project={}, mode={}, hash={}", 
                projectId, param.getMode(), contentHash.substring(0, 8));
        }).exceptionally(e -> {
            logger.warn("Failed to store in query cache: {}", e.getMessage());
            return null;
        });
    }
    
    /**
     * Invalidates all cached query results for a project.
     * 
     * <p>Should be called when the project's knowledge graph changes
     * (e.g., new documents added, entities modified).</p>
     * 
     * @param projectId the project ID
     * @return CompletableFuture with count of invalidated entries
     */
    public CompletableFuture<Integer> invalidate(@NotNull String projectId) {
        if (cacheStorage == null) {
            return CompletableFuture.completedFuture(0);
        }
        
        return cacheStorage.deleteByProject(projectId)
            .thenApply(count -> {
                logger.info("Invalidated {} cached queries for project {}", count, projectId);
                return count;
            })
            .exceptionally(e -> {
                logger.warn("Failed to invalidate query cache: {}", e.getMessage());
                return 0;
            });
    }
    
    /**
     * Computes a cache key for a query and its parameters.
     * 
     * <p>The key is a SHA-256 hash of the concatenated:</p>
     * <ul>
     *   <li>Query text</li>
     *   <li>Query mode</li>
     *   <li>Top-K value</li>
     *   <li>Chunk top-K value</li>
     * </ul>
     * 
     * @param query the query text
     * @param param the query parameters
     * @return SHA-256 hash as hex string
     */
    private String computeCacheKey(@NotNull String query, @NotNull QueryParam param) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(query);
        keyBuilder.append("|");
        keyBuilder.append(param.getMode().name());
        keyBuilder.append("|");
        keyBuilder.append(param.getTopK());
        keyBuilder.append("|");
        keyBuilder.append(param.getChunkTopK());
        
        return sha256Hash(keyBuilder.toString());
    }
    
    /**
     * Computes SHA-256 hash of a string.
     * 
     * @param input the input string
     * @return hex-encoded hash
     */
    private String sha256Hash(@NotNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Represents a cached query result.
     * 
     * <p>Contains the answer text and metadata needed to reconstruct
     * a LightRAGQueryResult. Source chunks are NOT cached to save space.</p>
     * 
     * @param answer the LLM-generated answer
     * @param mode the query mode used
     * @param totalSources the number of sources used
     */
    public record CachedQueryResult(
        @NotNull String answer,
        @NotNull QueryParam.Mode mode,
        int totalSources
    ) {
        /**
         * Converts this cached result to a LightRAGQueryResult.
         * 
         * <p>Note: Source chunks are not cached, so the returned result
         * will have an empty source list. This is acceptable because
         * cached responses are typically used for identical repeated queries.</p>
         * 
         * @return LightRAGQueryResult with cached answer
         */
        public LightRAGQueryResult toQueryResult() {
            return new LightRAGQueryResult(
                answer,
                List.of(),  // Sources not cached
                mode,
                totalSources
            );
        }
        
        /**
         * Serializes this result to JSON for storage.
         * 
         * @param result the query result to serialize
         * @return JSON string
         */
        public static String toJson(@NotNull LightRAGQueryResult result) {
            // Simple JSON format - can be enhanced with a proper JSON library if needed
            return String.format(
                "{\"answer\":\"%s\",\"mode\":\"%s\",\"totalSources\":%d}",
                escapeJson(result.answer()),
                result.mode().name(),
                result.totalSources()
            );
        }
        
        /**
         * Deserializes a cached result from JSON.
         * 
         * @param json the JSON string
         * @param mode the query mode (fallback if not in JSON)
         * @return CachedQueryResult
         */
        public static CachedQueryResult fromJson(@NotNull String json, @NotNull QueryParam.Mode mode) {
            // Simple JSON parsing - handles the format we create
            try {
                String answer = extractJsonField(json, "answer");
                String modeStr = extractJsonField(json, "mode");
                String totalSourcesStr = extractJsonField(json, "totalSources");
                
                QueryParam.Mode parsedMode = mode;
                if (modeStr != null && !modeStr.isEmpty()) {
                    try {
                        parsedMode = QueryParam.Mode.valueOf(modeStr);
                    } catch (IllegalArgumentException ignored) {
                        // Use fallback mode
                    }
                }
                
                int totalSources = 0;
                if (totalSourcesStr != null && !totalSourcesStr.isEmpty()) {
                    try {
                        totalSources = Integer.parseInt(totalSourcesStr);
                    } catch (NumberFormatException ignored) {
                        // Default to 0
                    }
                }
                
                return new CachedQueryResult(
                    answer != null ? unescapeJson(answer) : "",
                    parsedMode,
                    totalSources
                );
            } catch (Exception e) {
                // Fallback: treat entire JSON as the answer
                return new CachedQueryResult(json, mode, 0);
            }
        }
        
        /**
         * Extracts a field value from a simple JSON object.
         */
        private static @Nullable String extractJsonField(@NotNull String json, @NotNull String field) {
            String pattern = "\"" + field + "\":";
            int start = json.indexOf(pattern);
            if (start == -1) return null;
            
            start += pattern.length();
            
            // Skip whitespace
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            
            if (start >= json.length()) return null;
            
            // Check if it's a string value
            if (json.charAt(start) == '"') {
                start++;
                StringBuilder value = new StringBuilder();
                while (start < json.length() && json.charAt(start) != '"') {
                    if (json.charAt(start) == '\\' && start + 1 < json.length()) {
                        // Handle escape sequence
                        start++;
                        char escaped = json.charAt(start);
                        switch (escaped) {
                            case 'n' -> value.append('\n');
                            case 'r' -> value.append('\r');
                            case 't' -> value.append('\t');
                            case '"' -> value.append('"');
                            case '\\' -> value.append('\\');
                            default -> value.append(escaped);
                        }
                    } else {
                        value.append(json.charAt(start));
                    }
                    start++;
                }
                return value.toString();
            } else {
                // Numeric or other value
                StringBuilder value = new StringBuilder();
                while (start < json.length() && json.charAt(start) != ',' && json.charAt(start) != '}') {
                    value.append(json.charAt(start));
                    start++;
                }
                return value.toString().trim();
            }
        }
        
        /**
         * Escapes a string for JSON.
         */
        private static String escapeJson(@NotNull String text) {
            StringBuilder escaped = new StringBuilder();
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> escaped.append(c);
                }
            }
            return escaped.toString();
        }
        
        /**
         * Unescapes a JSON string.
         */
        private static String unescapeJson(@NotNull String text) {
            StringBuilder unescaped = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    switch (next) {
                        case 'n' -> { unescaped.append('\n'); i++; }
                        case 'r' -> { unescaped.append('\r'); i++; }
                        case 't' -> { unescaped.append('\t'); i++; }
                        case '"' -> { unescaped.append('"'); i++; }
                        case '\\' -> { unescaped.append('\\'); i++; }
                        default -> unescaped.append(c);
                    }
                } else {
                    unescaped.append(c);
                }
            }
            return unescaped.toString();
        }
    }
}
