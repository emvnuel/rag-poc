package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.llm.LLMFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based keyword extraction for query routing.
 * 
 * <p>Uses LLM to extract high-level (thematic) and low-level (entity) keywords
 * from user queries. Results are cached based on query hash to avoid redundant LLM calls.</p>
 * 
 * <h2>Prompt Format:</h2>
 * <p>The LLM is instructed to return keywords in a structured format:</p>
 * <pre>
 * HIGH_LEVEL_KEYWORDS: keyword1, keyword2, keyword3
 * LOW_LEVEL_KEYWORDS: entity1, entity2, entity3
 * </pre>
 * 
 * <h2>Caching:</h2>
 * <p>Results are cached in memory with configurable TTL. For production use,
 * consider using ExtractionCacheStorage for persistent caching.</p>
 * 
 * @see KeywordExtractor
 * @see KeywordResult
 */
public class LLMKeywordExtractor implements KeywordExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMKeywordExtractor.class);
    
    private final LLMFunction llmFunction;
    private final LightRAGExtractionConfig config;
    
    // In-memory cache: projectId:queryHash -> KeywordResult
    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();
    
    // Cache entry with expiration
    private record CachedResult(KeywordResult result, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    /**
     * System prompt for keyword extraction.
     * Instructs the LLM to categorize keywords as high-level (thematic) or low-level (entity).
     */
    private static final String KEYWORD_EXTRACTION_SYSTEM_PROMPT = """
        You are a query analysis expert. Your task is to extract keywords from a user query.
        
        Extract two types of keywords:
        
        1. HIGH_LEVEL_KEYWORDS: Thematic, conceptual, or relationship-focused keywords.
           These represent broad topics, themes, or the nature of relationships being asked about.
           Examples: "research trends", "business partnerships", "technical architecture", "historical events"
        
        2. LOW_LEVEL_KEYWORDS: Entity-focused, specific keywords.
           These are specific names, entities, or concrete concepts mentioned in the query.
           Examples: "OpenAI", "Python", "New York", "machine learning"
        
        Output format (MUST follow exactly):
        HIGH_LEVEL_KEYWORDS: keyword1, keyword2, keyword3
        LOW_LEVEL_KEYWORDS: entity1, entity2, entity3
        
        Rules:
        - Extract 1-5 keywords for each category
        - If no keywords fit a category, output: CATEGORY: none
        - Keywords should be lowercase
        - Remove stop words and generic terms
        - Focus on meaningful, search-relevant terms
        """;
    
    /**
     * User prompt template for keyword extraction.
     */
    private static final String KEYWORD_EXTRACTION_USER_PROMPT_TEMPLATE = 
        "Extract keywords from this query:\n\n%s";
    
    /**
     * Regex patterns for parsing LLM response.
     */
    private static final Pattern HIGH_LEVEL_PATTERN = 
        Pattern.compile("HIGH_LEVEL_KEYWORDS:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOW_LEVEL_PATTERN = 
        Pattern.compile("LOW_LEVEL_KEYWORDS:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    
    /**
     * Creates a new LLMKeywordExtractor.
     * 
     * @param llmFunction the LLM function for extraction
     * @param config configuration for caching TTL and feature toggle
     */
    public LLMKeywordExtractor(@NotNull LLMFunction llmFunction, @Nullable LightRAGExtractionConfig config) {
        this.llmFunction = llmFunction;
        this.config = config;
    }
    
    @Override
    public CompletableFuture<KeywordResult> extract(@NotNull String query, @Nullable String projectId) {
        // Check if keyword extraction is enabled
        if (config != null && !config.query().keywordExtraction().enabled()) {
            logger.debug("Keyword extraction disabled, returning empty result");
            return CompletableFuture.completedFuture(KeywordResult.empty(hashQuery(query)));
        }
        
        String queryHash = hashQuery(query);
        
        // Check cache first
        KeywordResult cached = getCached(queryHash, projectId);
        if (cached != null) {
            logger.debug("Returning cached keywords for query hash: {}", queryHash.substring(0, 8));
            return CompletableFuture.completedFuture(cached);
        }
        
        logger.debug("Extracting keywords for query: {}...", query.substring(0, Math.min(50, query.length())));
        
        // Build user prompt
        String userPrompt = String.format(KEYWORD_EXTRACTION_USER_PROMPT_TEMPLATE, query);
        
        // Call LLM
        return llmFunction.apply(userPrompt, KEYWORD_EXTRACTION_SYSTEM_PROMPT)
            .thenApply(response -> {
                KeywordResult result = parseResponse(response, queryHash);
                
                // Cache the result
                cacheResult(queryHash, projectId, result);
                
                logger.debug("Extracted {} high-level and {} low-level keywords", 
                    result.highLevelKeywords().size(), result.lowLevelKeywords().size());
                
                return result;
            })
            .exceptionally(e -> {
                logger.warn("Keyword extraction failed: {}", e.getMessage());
                return KeywordResult.empty(queryHash);
            });
    }
    
    @Override
    public KeywordResult getCached(@NotNull String queryHash, @Nullable String projectId) {
        String cacheKey = buildCacheKey(queryHash, projectId);
        CachedResult cached = cache.get(cacheKey);
        
        if (cached == null) {
            return null;
        }
        
        if (cached.isExpired()) {
            cache.remove(cacheKey);
            return null;
        }
        
        return cached.result();
    }
    
    /**
     * Parses the LLM response to extract keywords.
     * 
     * @param response the LLM response
     * @param queryHash the query hash for the result
     * @return parsed KeywordResult
     */
    private KeywordResult parseResponse(@NotNull String response, @NotNull String queryHash) {
        List<String> highLevel = new ArrayList<>();
        List<String> lowLevel = new ArrayList<>();
        
        // Parse high-level keywords
        Matcher highMatcher = HIGH_LEVEL_PATTERN.matcher(response);
        if (highMatcher.find()) {
            String keywords = highMatcher.group(1).trim();
            if (!keywords.equalsIgnoreCase("none")) {
                highLevel = parseKeywordList(keywords);
            }
        }
        
        // Parse low-level keywords
        Matcher lowMatcher = LOW_LEVEL_PATTERN.matcher(response);
        if (lowMatcher.find()) {
            String keywords = lowMatcher.group(1).trim();
            if (!keywords.equalsIgnoreCase("none")) {
                lowLevel = parseKeywordList(keywords);
            }
        }
        
        return KeywordResult.fresh(highLevel, lowLevel, queryHash);
    }
    
    /**
     * Parses a comma-separated keyword list.
     */
    private List<String> parseKeywordList(@NotNull String keywordsStr) {
        List<String> keywords = new ArrayList<>();
        
        for (String keyword : keywordsStr.split(",")) {
            String cleaned = keyword.trim().toLowerCase();
            // Filter out empty strings and "none"
            if (!cleaned.isEmpty() && !cleaned.equals("none")) {
                keywords.add(cleaned);
            }
        }
        
        return keywords;
    }
    
    /**
     * Caches a keyword result.
     */
    private void cacheResult(@NotNull String queryHash, @Nullable String projectId, @NotNull KeywordResult result) {
        int ttlSeconds = config != null ? config.query().keywordExtraction().cacheTtl() : 3600;
        
        if (ttlSeconds <= 0) {
            return; // Caching disabled
        }
        
        String cacheKey = buildCacheKey(queryHash, projectId);
        long expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L);
        
        // Create cached version with timestamp
        KeywordResult cachedResult = KeywordResult.cached(
            result.highLevelKeywords(), 
            result.lowLevelKeywords(), 
            result.queryHash()
        );
        
        cache.put(cacheKey, new CachedResult(cachedResult, expiresAt));
    }
    
    /**
     * Builds a cache key from query hash and project ID.
     */
    private String buildCacheKey(@NotNull String queryHash, @Nullable String projectId) {
        return (projectId != null ? projectId : "global") + ":" + queryHash;
    }
    
    /**
     * Clears the cache (for testing).
     */
    public void clearCache() {
        cache.clear();
    }
    
    /**
     * Returns the number of cached entries (for testing).
     */
    public int cacheSize() {
        return cache.size();
    }
}
