package br.edu.ifba.lightrag.merge;

/**
 * Strategy for merging entity descriptions during entity merge operations.
 * 
 * <p>When multiple entities are merged into one, their descriptions need to be
 * combined. This enum defines the available strategies for how to merge
 * descriptions.</p>
 * 
 * <h2>Usage Examples:</h2>
 * <pre>{@code
 * // Basic merge with concatenation (default)
 * MergeStrategy.CONCATENATE
 * 
 * // Keep only the first description
 * MergeStrategy.KEEP_FIRST
 * 
 * // Use LLM to create a unified description
 * MergeStrategy.LLM_SUMMARIZE
 * }</pre>
 * 
 * @since spec-007
 */
public enum MergeStrategy {
    
    /**
     * Join all descriptions with " | " separator.
     * 
     * <p>This is the default strategy. It preserves all information but may
     * result in longer descriptions.</p>
     * 
     * <p>Example:</p>
     * <pre>
     * Input:  ["AI is...", "Artificial Intelligence refers to..."]
     * Output: "AI is... | Artificial Intelligence refers to..."
     * </pre>
     */
    CONCATENATE(" | "),
    
    /**
     * Keep the first description (or target's existing description).
     * 
     * <p>This strategy discards additional descriptions and keeps only the
     * first one encountered.</p>
     */
    KEEP_FIRST(null),
    
    /**
     * Keep the longest description.
     * 
     * <p>This strategy assumes that longer descriptions contain more information
     * and are therefore more valuable.</p>
     */
    KEEP_LONGEST(null),
    
    /**
     * Use LLM to create unified description.
     * 
     * <p>This strategy sends all descriptions to the LLM and asks it to
     * produce a coherent summary. This costs tokens but produces the best
     * quality results.</p>
     * 
     * <p>Note: This strategy requires a configured LLM and will contribute
     * to token usage tracking.</p>
     */
    LLM_SUMMARIZE(null);
    
    private final String separator;
    
    MergeStrategy(String separator) {
        this.separator = separator;
    }
    
    /**
     * Gets the separator used for concatenation strategy.
     * 
     * @return The separator string, or null if not applicable
     */
    public String getSeparator() {
        return separator;
    }
    
    /**
     * Returns the default strategy (CONCATENATE).
     * 
     * @return The default merge strategy
     */
    public static MergeStrategy defaultStrategy() {
        return CONCATENATE;
    }
    
    /**
     * Parses a strategy from string, case-insensitive.
     * 
     * @param value The string value to parse
     * @return The matching strategy
     * @throws IllegalArgumentException if value doesn't match any strategy
     */
    public static MergeStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return defaultStrategy();
        }
        
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid merge strategy: '" + value + "'. Valid values are: " +
                "CONCATENATE, KEEP_FIRST, KEEP_LONGEST, LLM_SUMMARIZE"
            );
        }
    }
    
    /**
     * Checks if this strategy requires LLM calls.
     * 
     * @return true if this strategy uses LLM
     */
    public boolean requiresLLM() {
        return this == LLM_SUMMARIZE;
    }
}
