package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for summarizing accumulated entity/relation descriptions.
 * 
 * <p>When entities are mentioned across multiple chunks, their descriptions accumulate.
 * This interface provides methods to determine when summarization is needed and to
 * produce coherent summaries using LLM-based or rule-based strategies.</p>
 * 
 * <h2>Usage Pattern:</h2>
 * <pre>{@code
 * DescriptionSummarizer summarizer = ...;
 * List<String> descriptions = entity.getAccumulatedDescriptions();
 * 
 * if (summarizer.needsSummarization(descriptions)) {
 *     String summary = summarizer.summarize(
 *         entity.getEntityName(),
 *         descriptions,
 *         projectId
 *     ).join();
 *     entity = entity.withDescription(summary);
 * }
 * }</pre>
 * 
 * @see LLMDescriptionSummarizer for the LLM-based implementation
 */
public interface DescriptionSummarizer {
    
    /**
     * Determines if the given descriptions should be summarized.
     * 
     * <p>Implementations should check if the total token count exceeds
     * the configured threshold.</p>
     * 
     * @param descriptions List of accumulated descriptions
     * @return true if summarization is needed, false otherwise
     */
    boolean needsSummarization(@NotNull List<String> descriptions);
    
    /**
     * Summarizes multiple descriptions into a coherent single description.
     * 
     * <p>This method uses LLM or other strategies to produce a coherent summary
     * that captures the key information from all input descriptions.</p>
     * 
     * @param entityName The name of the entity being summarized (for context)
     * @param descriptions List of descriptions to summarize
     * @param projectId The project ID (for caching and isolation)
     * @return CompletableFuture with the summarized description
     */
    CompletableFuture<String> summarize(
        @NotNull String entityName,
        @NotNull List<String> descriptions,
        @NotNull String projectId
    );
    
    /**
     * Summarizes descriptions with type context for better LLM understanding.
     * 
     * @param entityName The name of the entity being summarized
     * @param entityType The type of the entity (PERSON, ORGANIZATION, etc.)
     * @param descriptions List of descriptions to summarize
     * @param projectId The project ID
     * @return CompletableFuture with the summarized description
     */
    default CompletableFuture<String> summarize(
        @NotNull String entityName,
        @NotNull String entityType,
        @NotNull List<String> descriptions,
        @NotNull String projectId
    ) {
        return summarize(entityName, descriptions, projectId);
    }
    
    /**
     * Gets the token count of the combined descriptions.
     * 
     * @param descriptions List of descriptions
     * @return Estimated total token count
     */
    int estimateTokenCount(@NotNull List<String> descriptions);
    
    /**
     * Returns the configured summarization threshold.
     * 
     * @return The token threshold that triggers summarization
     */
    int getSummarizationThreshold();
}
