package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.core.TokenUsage;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.utils.TokenUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent keyword extractor that uses PostgreSQL for cache storage.
 * 
 * <p>This implementation stores keyword extraction results in the extraction_cache table
 * for persistence across restarts. It also maintains a short-lived in-memory cache
 * for performance optimization within a single session.</p>
 * 
 * <h2>Cache Strategy:</h2>
 * <ol>
 *   <li>Check in-memory L1 cache (short TTL, per-session)</li>
 *   <li>Check PostgreSQL L2 cache (persistent)</li>
 *   <li>If not found, call LLM and store in both caches</li>
 * </ol>
 * 
 * <h2>Prompt Format:</h2>
 * <p>The LLM is instructed to return keywords in a structured format:</p>
 * <pre>
 * HIGH_LEVEL_KEYWORDS: keyword1, keyword2, keyword3
 * LOW_LEVEL_KEYWORDS: entity1, entity2, entity3
 * </pre>
 * 
 * @see KeywordExtractor
 * @see KeywordResult
 * @see ExtractionCacheStorage
 */
@ApplicationScoped
public class PersistentKeywordExtractor implements KeywordExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(PersistentKeywordExtractor.class);
    
    private final LLMFunction llmFunction;
    private final ExtractionCacheStorage cacheStorage;
    private final LightRAGExtractionConfig config;
    
    /**
     * In-memory L1 cache for hot queries.
     * Short TTL (5 minutes) to reduce PostgreSQL reads for repeated queries.
     */
    private final ConcurrentHashMap<String, L1CacheEntry> l1Cache = new ConcurrentHashMap<>();
    
    /** L1 cache TTL in milliseconds (5 minutes) */
    private static final long L1_CACHE_TTL_MS = 5 * 60 * 1000L;
    
    /** Maximum L1 cache size before cleanup */
    private static final int L1_CACHE_MAX_SIZE = 1000;
    
    /**
     * L1 cache entry with expiration.
     */
    private record L1CacheEntry(KeywordResult result, long expiresAt) {
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
     * Creates a new PersistentKeywordExtractor.
     * 
     * @param llmFunction the LLM function for extraction
     * @param cacheStorage the persistent cache storage
     * @param config configuration for caching TTL and feature toggle
     */
    @Inject
    public PersistentKeywordExtractor(
        LLMFunction llmFunction,
        ExtractionCacheStorage cacheStorage,
        LightRAGExtractionConfig config
    ) {
        this.llmFunction = llmFunction;
        this.cacheStorage = cacheStorage;
        this.config = config;
    }
    
    /**
     * Constructor for manual instantiation (testing, non-CDI contexts).
     */
    public PersistentKeywordExtractor(
        LLMFunction llmFunction,
        ExtractionCacheStorage cacheStorage
    ) {
        this.llmFunction = llmFunction;
        this.cacheStorage = cacheStorage;
        this.config = null;
    }
    
    @Override
    public CompletableFuture<KeywordResult> extract(@NotNull String query, @Nullable String projectId) {
        // Check if keyword extraction is enabled
        if (config != null && !config.query().keywordExtraction().enabled()) {
            logger.debug("Keyword extraction disabled, returning empty result");
            return CompletableFuture.completedFuture(KeywordResult.empty(hashQuery(query)));
        }
        
        String queryHash = hashQuery(query);
        String cacheKey = buildCacheKey(queryHash, projectId);
        
        // 1. Check L1 cache first (in-memory)
        KeywordResult l1Result = getFromL1Cache(cacheKey);
        if (l1Result != null) {
            logger.debug("L1 cache hit for query hash: {}", queryHash.substring(0, 8));
            return CompletableFuture.completedFuture(l1Result);
        }
        
        // 2. Check L2 cache (PostgreSQL)
        if (projectId != null) {
            return cacheStorage.get(projectId, CacheType.KEYWORD_EXTRACTION, queryHash)
                .thenCompose(cached -> {
                    if (cached.isPresent()) {
                        logger.debug("L2 cache hit for query hash: {}", queryHash.substring(0, 8));
                        KeywordResult result = parseStoredResult(cached.get().result(), queryHash);
                        putToL1Cache(cacheKey, result);
                        return CompletableFuture.completedFuture(result);
                    }
                    
                    // 3. No cache hit, call LLM
                    return extractFromLLM(query, queryHash, projectId, cacheKey);
                });
        }
        
        // No project ID, just call LLM with L1 caching
        return extractFromLLM(query, queryHash, null, cacheKey);
    }
    
    /**
     * Extracts keywords from LLM and caches the result.
     */
    private CompletableFuture<KeywordResult> extractFromLLM(
        String query, 
        String queryHash, 
        @Nullable String projectId,
        String cacheKey
    ) {
        logger.debug("Extracting keywords from LLM for query: {}...", 
            query.substring(0, Math.min(50, query.length())));
        
        String userPrompt = String.format(KEYWORD_EXTRACTION_USER_PROMPT_TEMPLATE, query);
        Map<String, Object> kwargs = Map.of("operation_type", TokenUsage.OP_KEYWORD_EXTRACTION);
        
        return llmFunction.apply(userPrompt, KEYWORD_EXTRACTION_SYSTEM_PROMPT, null, kwargs)
            .thenCompose(response -> {
                KeywordResult result = parseResponse(response, queryHash);
                
                // Store in L1 cache
                putToL1Cache(cacheKey, result);
                
                // Store in L2 cache (PostgreSQL) if project ID provided
                if (projectId != null) {
                    String storedValue = serializeResult(result);
                    int tokensUsed = TokenUtil.estimateTokens(response);
                    
                    return cacheStorage.store(
                        projectId,
                        CacheType.KEYWORD_EXTRACTION,
                        null,
                        queryHash,
                        storedValue,
                        tokensUsed
                    ).thenApply(cacheId -> {
                        logger.debug("Cached keywords with ID: {} for query hash: {}", 
                            cacheId, queryHash.substring(0, 8));
                        return result;
                    });
                }
                
                logger.debug("Extracted {} high-level and {} low-level keywords", 
                    result.highLevelKeywords().size(), result.lowLevelKeywords().size());
                
                return CompletableFuture.completedFuture(result);
            })
            .exceptionally(e -> {
                logger.warn("Keyword extraction failed: {}", e.getMessage());
                return KeywordResult.empty(queryHash);
            });
    }
    
    @Override
    public KeywordResult getCached(@NotNull String queryHash, @Nullable String projectId) {
        String cacheKey = buildCacheKey(queryHash, projectId);
        
        // Check L1 cache
        KeywordResult l1Result = getFromL1Cache(cacheKey);
        if (l1Result != null) {
            return l1Result;
        }
        
        // For synchronous access, we can't easily check L2
        // This method is primarily for L1 cache access
        return null;
    }
    
    /**
     * Parses the LLM response to extract keywords.
     */
    private KeywordResult parseResponse(@NotNull String response, @NotNull String queryHash) {
        List<String> highLevel = new ArrayList<>();
        List<String> lowLevel = new ArrayList<>();
        
        Matcher highMatcher = HIGH_LEVEL_PATTERN.matcher(response);
        if (highMatcher.find()) {
            String keywords = highMatcher.group(1).trim();
            if (!keywords.equalsIgnoreCase("none")) {
                highLevel = parseKeywordList(keywords);
            }
        }
        
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
            if (!cleaned.isEmpty() && !cleaned.equals("none")) {
                keywords.add(cleaned);
            }
        }
        
        return keywords;
    }
    
    /**
     * Serializes KeywordResult for storage.
     * Format: HIGH:keyword1,keyword2|LOW:entity1,entity2
     */
    private String serializeResult(KeywordResult result) {
        String high = String.join(",", result.highLevelKeywords());
        String low = String.join(",", result.lowLevelKeywords());
        return "HIGH:" + high + "|LOW:" + low;
    }
    
    /**
     * Parses stored result back to KeywordResult.
     */
    private KeywordResult parseStoredResult(String stored, String queryHash) {
        List<String> highLevel = new ArrayList<>();
        List<String> lowLevel = new ArrayList<>();
        
        String[] parts = stored.split("\\|");
        for (String part : parts) {
            if (part.startsWith("HIGH:")) {
                String keywords = part.substring(5);
                if (!keywords.isEmpty()) {
                    highLevel = List.of(keywords.split(","));
                }
            } else if (part.startsWith("LOW:")) {
                String keywords = part.substring(4);
                if (!keywords.isEmpty()) {
                    lowLevel = List.of(keywords.split(","));
                }
            }
        }
        
        return KeywordResult.cached(highLevel, lowLevel, queryHash);
    }
    
    /**
     * Gets result from L1 cache if not expired.
     */
    @Nullable
    private KeywordResult getFromL1Cache(String cacheKey) {
        L1CacheEntry entry = l1Cache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        
        if (entry.isExpired()) {
            l1Cache.remove(cacheKey);
            return null;
        }
        
        return entry.result();
    }
    
    /**
     * Stores result in L1 cache with TTL.
     */
    private void putToL1Cache(String cacheKey, KeywordResult result) {
        // Cleanup if cache is too large
        if (l1Cache.size() >= L1_CACHE_MAX_SIZE) {
            cleanupL1Cache();
        }
        
        long expiresAt = System.currentTimeMillis() + L1_CACHE_TTL_MS;
        KeywordResult cachedResult = KeywordResult.cached(
            result.highLevelKeywords(),
            result.lowLevelKeywords(),
            result.queryHash()
        );
        l1Cache.put(cacheKey, new L1CacheEntry(cachedResult, expiresAt));
    }
    
    /**
     * Removes expired entries from L1 cache.
     */
    private void cleanupL1Cache() {
        long now = System.currentTimeMillis();
        l1Cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        
        // If still too large, remove oldest entries
        if (l1Cache.size() >= L1_CACHE_MAX_SIZE) {
            int toRemove = l1Cache.size() - (L1_CACHE_MAX_SIZE / 2);
            l1Cache.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().expiresAt(), b.getValue().expiresAt()))
                .limit(toRemove)
                .forEach(entry -> l1Cache.remove(entry.getKey()));
        }
        
        logger.debug("L1 cache cleanup complete, current size: {}", l1Cache.size());
    }
    
    /**
     * Builds a cache key from query hash and project ID.
     */
    private String buildCacheKey(@NotNull String queryHash, @Nullable String projectId) {
        return (projectId != null ? projectId : "global") + ":" + queryHash;
    }
    
    /**
     * Clears the L1 cache (for testing).
     */
    public void clearL1Cache() {
        l1Cache.clear();
    }
    
    /**
     * Returns the number of L1 cache entries (for testing/monitoring).
     */
    public int l1CacheSize() {
        return l1Cache.size();
    }
    
    /**
     * Checks if jtokkit is available for accurate token counting.
     */
    public boolean isExactTokenCountingAvailable() {
        return TokenUtil.isExactCountingAvailable();
    }
}
