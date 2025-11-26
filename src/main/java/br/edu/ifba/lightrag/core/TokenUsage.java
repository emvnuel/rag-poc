package br.edu.ifba.lightrag.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Token consumption record for a single LLM operation.
 * 
 * <p>Represents the token usage for one operation (completion, embedding, etc.)
 * and is aggregated by {@link TokenTracker} to provide session-level totals.
 * 
 * <p>This is an immutable record - instances cannot be modified after creation.
 * 
 * @param operationType Type of operation (INGESTION, QUERY, SUMMARIZATION, KEYWORD_EXTRACTION, RERANK)
 * @param modelName The LLM model used for this operation
 * @param inputTokens Number of tokens sent to the LLM
 * @param outputTokens Number of tokens received from the LLM
 * @param timestamp When this operation occurred
 */
public record TokenUsage(
    String operationType,
    String modelName,
    int inputTokens,
    int outputTokens,
    Instant timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public TokenUsage {
        Objects.requireNonNull(operationType, "operationType must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must be >= 0, got: " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0, got: " + outputTokens);
        }
    }
    
    /**
     * Creates a TokenUsage with the current timestamp.
     * 
     * @param operationType Type of operation
     * @param modelName The LLM model used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @return New TokenUsage instance with current timestamp
     */
    public static TokenUsage now(String operationType, String modelName, int inputTokens, int outputTokens) {
        return new TokenUsage(operationType, modelName, inputTokens, outputTokens, Instant.now());
    }
    
    /**
     * Returns the total tokens (input + output).
     * 
     * @return Sum of input and output tokens
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
    
    /**
     * Common operation type constants.
     */
    public static final String OP_INGESTION = "INGESTION";
    public static final String OP_QUERY = "QUERY";
    public static final String OP_SUMMARIZATION = "SUMMARIZATION";
    public static final String OP_KEYWORD_EXTRACTION = "KEYWORD_EXTRACTION";
    public static final String OP_RERANK = "RERANK";
    public static final String OP_EMBEDDING = "EMBEDDING";
    public static final String OP_MERGE = "MERGE";
    public static final String OP_GLEANING = "GLEANING";
}
