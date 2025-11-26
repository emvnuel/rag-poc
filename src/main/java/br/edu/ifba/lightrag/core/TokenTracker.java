package br.edu.ifba.lightrag.core;

import java.util.List;

/**
 * Interface for tracking token usage across LLM operations within a request.
 * 
 * <p>Implementations should be request-scoped to ensure isolation between
 * concurrent requests. The tracker aggregates individual {@link TokenUsage}
 * records and provides a summary for HTTP response headers.
 * 
 * <p>Contract:
 * <ul>
 *   <li>MUST be request-scoped (isolated per HTTP request)</li>
 *   <li>MUST be thread-safe (parallel LLM calls)</li>
 *   <li>MUST track input and output tokens separately</li>
 *   <li>MUST track by operation type for debugging</li>
 * </ul>
 * 
 * @see TokenUsage
 * @see TokenSummary
 * @see TokenTrackerImpl
 */
public interface TokenTracker {
    
    /**
     * Records token usage for an operation.
     * 
     * @param usage Token usage record to track
     */
    void track(TokenUsage usage);
    
    /**
     * Convenience method to track token usage with current timestamp.
     * 
     * @param operationType Type of operation (e.g., INGESTION, QUERY)
     * @param modelName The LLM model used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     */
    default void track(String operationType, String modelName, int inputTokens, int outputTokens) {
        track(TokenUsage.now(operationType, modelName, inputTokens, outputTokens));
    }
    
    /**
     * Gets aggregated token summary.
     * 
     * @return Summary with totals and breakdown by operation type
     */
    TokenSummary getSummary();
    
    /**
     * Resets all counters.
     * 
     * <p>Used for testing or when a logical session boundary is reached.
     */
    void reset();
    
    /**
     * Gets raw usage records.
     * 
     * <p>Useful for detailed logging or debugging.
     * 
     * @return List of all recorded token usages
     */
    List<TokenUsage> getUsages();
    
    /**
     * Returns the total input tokens tracked so far.
     * 
     * @return Total input tokens
     */
    int getTotalInputTokens();
    
    /**
     * Returns the total output tokens tracked so far.
     * 
     * @return Total output tokens
     */
    int getTotalOutputTokens();
}
