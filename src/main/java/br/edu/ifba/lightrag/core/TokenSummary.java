package br.edu.ifba.lightrag.core;

import java.util.Map;
import java.util.Objects;

/**
 * Aggregated token usage summary for a session/request.
 * 
 * <p>Provides totals and breakdown by operation type for all LLM operations
 * within a single request. This summary is typically exposed via HTTP response
 * headers (X-Token-Input, X-Token-Output).
 * 
 * @param totalInputTokens Sum of all input tokens across operations
 * @param totalOutputTokens Sum of all output tokens across operations
 * @param byOperationType Breakdown of total tokens by operation type
 */
public record TokenSummary(
    int totalInputTokens,
    int totalOutputTokens,
    Map<String, Integer> byOperationType
) {
    /**
     * Compact constructor with validation.
     */
    public TokenSummary {
        Objects.requireNonNull(byOperationType, "byOperationType must not be null");
        if (totalInputTokens < 0) {
            throw new IllegalArgumentException("totalInputTokens must be >= 0");
        }
        if (totalOutputTokens < 0) {
            throw new IllegalArgumentException("totalOutputTokens must be >= 0");
        }
        // Make the map immutable
        byOperationType = Map.copyOf(byOperationType);
    }
    
    /**
     * Returns the total tokens (input + output).
     * 
     * @return Sum of all tokens
     */
    public int totalTokens() {
        return totalInputTokens + totalOutputTokens;
    }
    
    /**
     * Creates an empty summary with zero tokens.
     * 
     * @return Empty TokenSummary
     */
    public static TokenSummary empty() {
        return new TokenSummary(0, 0, Map.of());
    }
}
