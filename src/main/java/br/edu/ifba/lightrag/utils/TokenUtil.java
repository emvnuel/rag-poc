package br.edu.ifba.lightrag.utils;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for token counting and text chunking.
 * Uses jtokkit for accurate GPT-compatible token counting.
 * 
 * <p>This implementation uses the cl100k_base encoding which is compatible with
 * GPT-4, GPT-3.5-turbo, and text-embedding-ada-002 models.</p>
 */
public final class TokenUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenUtil.class);
    
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    
    /**
     * Fallback approximation when jtokkit is unavailable.
     * Average of ~4 characters per token for English text.
     */
    private static final double AVG_CHARS_PER_TOKEN = 4.0;
    
    /** Default max context tokens if config is not available */
    public static final int DEFAULT_MAX_TOKENS = 4000;
    
    /** Default entity budget ratio */
    public static final double DEFAULT_ENTITY_BUDGET_RATIO = 0.4;
    
    /** Default relation budget ratio */
    public static final double DEFAULT_RELATION_BUDGET_RATIO = 0.3;
    
    /** Default chunk budget ratio */
    public static final double DEFAULT_CHUNK_BUDGET_RATIO = 0.3;
    
    /**
     * Lazily initialized encoding registry and encoder.
     * Uses cl100k_base which is compatible with GPT-4, GPT-3.5-turbo.
     */
    private static volatile Encoding encoding;
    private static volatile boolean initializationFailed = false;
    
    private TokenUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Gets or initializes the cl100k_base encoding.
     * Thread-safe lazy initialization.
     * 
     * @return the encoding, or null if initialization failed
     */
    @Nullable
    private static Encoding getEncoding() {
        if (encoding == null && !initializationFailed) {
            synchronized (TokenUtil.class) {
                if (encoding == null && !initializationFailed) {
                    try {
                        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
                        encoding = registry.getEncoding(EncodingType.CL100K_BASE);
                        logger.info("Initialized jtokkit with cl100k_base encoding for accurate token counting");
                    } catch (Exception e) {
                        initializationFailed = true;
                        logger.warn("Failed to initialize jtokkit, falling back to approximation: {}", e.getMessage());
                    }
                }
            }
        }
        return encoding;
    }
    
    /**
     * Estimates token count for a given text using jtokkit.
     * Falls back to character-based approximation if jtokkit is unavailable.
     *
     * @param text The input text
     * @return Token count (exact with jtokkit, approximate otherwise)
     */
    public static int estimateTokens(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                return enc.countTokens(text);
            } catch (Exception e) {
                logger.debug("Token counting failed, using approximation: {}", e.getMessage());
            }
        }
        
        // Fallback to approximation
        return estimateTokensApproximate(text);
    }
    
    /**
     * Estimates token count using character-based approximation.
     * Used as fallback when jtokkit is unavailable.
     *
     * @param text The input text
     * @return Approximate token count
     */
    public static int estimateTokensApproximate(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / AVG_CHARS_PER_TOKEN);
    }
    
    /**
     * Estimates token count, treating null as empty string.
     *
     * @param text The input text (nullable)
     * @return Token count, 0 for null
     */
    public static int estimateTokensSafe(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return estimateTokens(text);
    }
    
    /**
     * Encodes text to token IDs using jtokkit.
     * Returns empty list if jtokkit is unavailable.
     *
     * @param text The input text
     * @return List of token IDs
     */
    @NotNull
    public static List<Integer> encode(@NotNull String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                return enc.encode(text).boxed();
            } catch (Exception e) {
                logger.debug("Encoding failed: {}", e.getMessage());
            }
        }
        
        return List.of();
    }
    
    /**
     * Decodes token IDs back to text using jtokkit.
     * Returns empty string if jtokkit is unavailable.
     *
     * @param tokens List of token IDs
     * @return Decoded text
     */
    @NotNull
    public static String decode(@NotNull List<Integer> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                // Convert List<Integer> to IntArrayList
                com.knuddels.jtokkit.api.IntArrayList intList = new com.knuddels.jtokkit.api.IntArrayList(tokens.size());
                for (Integer token : tokens) {
                    intList.add(token);
                }
                return enc.decode(intList);
            } catch (Exception e) {
                logger.debug("Decoding failed: {}", e.getMessage());
            }
        }
        
        return "";
    }
    
    /**
     * Checks if jtokkit is available and working.
     *
     * @return true if exact token counting is available
     */
    public static boolean isExactCountingAvailable() {
        return getEncoding() != null;
    }
    
    // =========================================================================
    // Token Budget Allocation
    // =========================================================================
    
    /**
     * Gets the maximum context tokens from config or returns default.
     *
     * @param config extraction config (nullable)
     * @return max tokens
     */
    public static int getMaxTokens(@Nullable LightRAGExtractionConfig config) {
        return config != null 
            ? config.query().context().maxTokens() 
            : DEFAULT_MAX_TOKENS;
    }
    
    /**
     * Calculates entity token budget based on config ratios.
     *
     * @param config extraction config (nullable)
     * @return entity token budget
     */
    public static int getEntityBudget(@Nullable LightRAGExtractionConfig config) {
        int maxTokens = getMaxTokens(config);
        double ratio = config != null 
            ? config.query().context().entityBudgetRatio() 
            : DEFAULT_ENTITY_BUDGET_RATIO;
        return (int) (maxTokens * ratio);
    }
    
    /**
     * Calculates relation token budget based on config ratios.
     *
     * @param config extraction config (nullable)
     * @return relation token budget
     */
    public static int getRelationBudget(@Nullable LightRAGExtractionConfig config) {
        int maxTokens = getMaxTokens(config);
        double ratio = config != null 
            ? config.query().context().relationBudgetRatio() 
            : DEFAULT_RELATION_BUDGET_RATIO;
        return (int) (maxTokens * ratio);
    }
    
    /**
     * Calculates chunk token budget based on config ratios.
     *
     * @param config extraction config (nullable)
     * @return chunk token budget
     */
    public static int getChunkBudget(@Nullable LightRAGExtractionConfig config) {
        int maxTokens = getMaxTokens(config);
        double ratio = config != null 
            ? config.query().context().chunkBudgetRatio() 
            : DEFAULT_CHUNK_BUDGET_RATIO;
        return (int) (maxTokens * ratio);
    }
    
    /**
     * Token budget allocation result for multiple source types.
     * 
     * @param entityBudget tokens allocated for entities
     * @param relationBudget tokens allocated for relations
     * @param chunkBudget tokens allocated for chunks
     * @param totalBudget total tokens available
     */
    public record BudgetAllocation(
        int entityBudget,
        int relationBudget,
        int chunkBudget,
        int totalBudget
    ) {
        /**
         * Creates allocation with default ratios.
         */
        public static BudgetAllocation withDefaults(int totalBudget) {
            return new BudgetAllocation(
                (int) (totalBudget * DEFAULT_ENTITY_BUDGET_RATIO),
                (int) (totalBudget * DEFAULT_RELATION_BUDGET_RATIO),
                (int) (totalBudget * DEFAULT_CHUNK_BUDGET_RATIO),
                totalBudget
            );
        }
        
        /**
         * Creates allocation from config.
         */
        public static BudgetAllocation fromConfig(@Nullable LightRAGExtractionConfig config) {
            int total = getMaxTokens(config);
            return new BudgetAllocation(
                getEntityBudget(config),
                getRelationBudget(config),
                getChunkBudget(config),
                total
            );
        }
    }
    
    /**
     * Calculates budget allocation for all source types.
     *
     * @param config extraction config (nullable)
     * @return budget allocation for entities, relations, and chunks
     */
    public static BudgetAllocation calculateBudgetAllocation(@Nullable LightRAGExtractionConfig config) {
        return BudgetAllocation.fromConfig(config);
    }
    
    /**
     * Truncates text to fit within a token budget.
     * Uses exact token counting when available.
     *
     * @param text the text to truncate
     * @param maxTokens maximum tokens allowed
     * @return truncated text
     */
    @NotNull
    public static String truncateToTokenLimit(@NotNull String text, int maxTokens) {
        if (maxTokens <= 0) {
            return "";
        }
        
        int currentTokens = estimateTokens(text);
        if (currentTokens <= maxTokens) {
            return text;
        }
        
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                // Use exact truncation with jtokkit
                var tokens = enc.encode(text);
                if (tokens.size() <= maxTokens) {
                    return text;
                }
                // Truncate to maxTokens - reserve space for ellipsis
                int truncateAt = Math.max(0, maxTokens - 1);
                com.knuddels.jtokkit.api.IntArrayList truncated = new com.knuddels.jtokkit.api.IntArrayList(truncateAt);
                for (int i = 0; i < truncateAt && i < tokens.size(); i++) {
                    truncated.add(tokens.get(i));
                }
                return enc.decode(truncated) + "...";
            } catch (Exception e) {
                logger.debug("Exact truncation failed, using approximation: {}", e.getMessage());
            }
        }
        
        // Fallback: character-based truncation
        int targetChars = (int) (maxTokens * AVG_CHARS_PER_TOKEN);
        if (targetChars >= text.length()) {
            return text;
        }
        
        return text.substring(0, targetChars - 3) + "...";
    }
    
    /**
     * Checks if adding more content would exceed token budget.
     *
     * @param currentTokens current accumulated tokens
     * @param additionalText text to potentially add
     * @param maxTokens maximum tokens allowed
     * @return true if adding would exceed budget
     */
    public static boolean wouldExceedBudget(int currentTokens, @NotNull String additionalText, int maxTokens) {
        return currentTokens + estimateTokens(additionalText) > maxTokens;
    }
    
    /**
     * Chunks text into smaller pieces based on token limit.
     * Attempts to split on sentence boundaries when possible.
     *
     * @param text The input text
     * @param maxTokens Maximum tokens per chunk
     * @param overlapTokens Number of overlapping tokens between chunks
     * @return List of text chunks
     */
    @NotNull
    public static List<String> chunkText(
        @NotNull String text,
        int maxTokens,
        int overlapTokens
    ) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException(
                "overlapTokens must be non-negative and less than maxTokens"
            );
        }
        
        List<String> chunks = new ArrayList<>();
        if (text.isEmpty()) {
            return chunks;
        }
        
        // Split into sentences (simple approach)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        
        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            
            // If single sentence exceeds maxTokens, split it by tokens
            if (sentenceTokens > maxTokens) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                
                // Split long sentence into token-based chunks
                chunks.addAll(chunkByTokens(sentence, maxTokens, overlapTokens));
                continue;
            }
            
            // Check if adding this sentence would exceed limit
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                
                // Handle overlap by keeping last few tokens
                if (overlapTokens > 0) {
                    String overlap = getLastNTokens(currentChunk.toString(), overlapTokens);
                    currentChunk = new StringBuilder(overlap);
                    currentTokens = estimateTokens(overlap);
                } else {
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
            }
            
            currentChunk.append(sentence).append(" ");
            currentTokens += sentenceTokens;
        }
        
        // Add remaining chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks;
    }
    
    /**
     * Chunks text by token count when sentence-based chunking isn't possible.
     * Uses jtokkit for exact chunking when available.
     */
    @NotNull
    private static List<String> chunkByTokens(
        @NotNull String text,
        int maxTokens,
        int overlapTokens
    ) {
        List<String> chunks = new ArrayList<>();
        
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                var tokens = enc.encode(text);
                int stride = maxTokens - overlapTokens;
                
                for (int i = 0; i < tokens.size(); i += stride) {
                    int end = Math.min(i + maxTokens, tokens.size());
                    com.knuddels.jtokkit.api.IntArrayList chunkTokens = new com.knuddels.jtokkit.api.IntArrayList(end - i);
                    for (int j = i; j < end; j++) {
                        chunkTokens.add(tokens.get(j));
                    }
                    chunks.add(enc.decode(chunkTokens));
                    
                    if (end >= tokens.size()) {
                        break;
                    }
                }
                return chunks;
            } catch (Exception e) {
                logger.debug("Token-based chunking failed, using character fallback: {}", e.getMessage());
            }
        }
        
        // Fallback to character-based chunking
        return chunkByCharacters(text, maxTokens, overlapTokens);
    }
    
    /**
     * Chunks text by characters when token-based chunking isn't available.
     */
    @NotNull
    private static List<String> chunkByCharacters(
        @NotNull String text,
        int maxTokens,
        int overlapTokens
    ) {
        List<String> chunks = new ArrayList<>();
        int maxChars = (int) (maxTokens * AVG_CHARS_PER_TOKEN);
        int overlapChars = (int) (overlapTokens * AVG_CHARS_PER_TOKEN);
        int stride = maxChars - overlapChars;
        
        for (int i = 0; i < text.length(); i += stride) {
            int end = Math.min(i + maxChars, text.length());
            chunks.add(text.substring(i, end));
            
            if (end >= text.length()) {
                break;
            }
        }
        
        return chunks;
    }
    
    /**
     * Extracts the last N tokens from text.
     * Uses jtokkit for exact extraction when available.
     */
    @NotNull
    private static String getLastNTokens(@NotNull String text, int n) {
        Encoding enc = getEncoding();
        if (enc != null) {
            try {
                var tokens = enc.encode(text);
                if (tokens.size() <= n) {
                    return text;
                }
                int start = tokens.size() - n;
                com.knuddels.jtokkit.api.IntArrayList lastTokens = new com.knuddels.jtokkit.api.IntArrayList(n);
                for (int i = start; i < tokens.size(); i++) {
                    lastTokens.add(tokens.get(i));
                }
                return enc.decode(lastTokens);
            } catch (Exception e) {
                logger.debug("Token extraction failed, using character fallback: {}", e.getMessage());
            }
        }
        
        // Fallback to character-based
        int targetChars = (int) (n * AVG_CHARS_PER_TOKEN);
        if (text.length() <= targetChars) {
            return text;
        }
        return text.substring(text.length() - targetChars);
    }
    
    /**
     * Counts words in text (simple whitespace-based).
     */
    public static int countWords(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return WHITESPACE.split(text.trim()).length;
    }
}
