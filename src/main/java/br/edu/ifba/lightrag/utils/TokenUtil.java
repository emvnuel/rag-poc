package br.edu.ifba.lightrag.utils;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for token counting and text chunking.
 * Uses approximations for token counting (more accurate implementations
 * would use tiktoken or similar tokenizer libraries).
 */
public final class TokenUtil {
    
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final double AVG_CHARS_PER_TOKEN = 4.0; // Rough approximation
    
    /** Default max context tokens if config is not available */
    public static final int DEFAULT_MAX_TOKENS = 4000;
    
    /** Default entity budget ratio */
    public static final double DEFAULT_ENTITY_BUDGET_RATIO = 0.4;
    
    /** Default relation budget ratio */
    public static final double DEFAULT_RELATION_BUDGET_RATIO = 0.3;
    
    /** Default chunk budget ratio */
    public static final double DEFAULT_CHUNK_BUDGET_RATIO = 0.3;
    
    private TokenUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Estimates token count for a given text.
     * Uses a simple heuristic: ~4 characters per token on average.
     * For production use, integrate a proper tokenizer (e.g., tiktoken port).
     *
     * @param text The input text
     * @return Estimated token count
     */
    public static int estimateTokens(@NotNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / AVG_CHARS_PER_TOKEN);
    }
    
    /**
     * Estimates token count, treating null as empty string.
     *
     * @param text The input text (nullable)
     * @return Estimated token count, 0 for null
     */
    public static int estimateTokensSafe(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return estimateTokens(text);
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
        
        // Estimate character count for target tokens
        int targetChars = (int) (maxTokens * AVG_CHARS_PER_TOKEN);
        if (targetChars >= text.length()) {
            return text;
        }
        
        // Truncate and add ellipsis
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
            
            // If single sentence exceeds maxTokens, split it by characters
            if (sentenceTokens > maxTokens) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                
                // Split long sentence into character-based chunks
                chunks.addAll(chunkByCharacters(sentence, maxTokens, overlapTokens));
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
     * Chunks text by characters when sentence-based chunking isn't possible.
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
     * Extracts the last N tokens from text (approximation).
     */
    @NotNull
    private static String getLastNTokens(@NotNull String text, int n) {
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
