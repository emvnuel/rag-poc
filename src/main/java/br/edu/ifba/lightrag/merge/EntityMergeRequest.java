package br.edu.ifba.lightrag.merge;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request DTO for entity merge operations.
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Request body for POST /projects/{projectId}/entities/merge
 * {
 *   "sourceEntities": ["AI", "Artificial Intelligence", "Machine Intelligence"],
 *   "targetEntity": "Artificial Intelligence",
 *   "strategy": "LLM_SUMMARIZE",
 *   "targetEntityData": {
 *     "type": "CONCEPT"
 *   }
 * }
 * }</pre>
 * 
 * @param sourceEntities List of entity names to merge into the target (required, non-empty)
 * @param targetEntity Name of the entity to merge into (required, can be existing or new)
 * @param strategy Strategy for merging descriptions (optional, defaults to CONCATENATE)
 * @param targetEntityData Optional data to set on the target entity (e.g., type override)
 * @since spec-007
 */
public record EntityMergeRequest(
    @JsonProperty("sourceEntities")
    @NotNull List<String> sourceEntities,
    
    @JsonProperty("targetEntity")
    @NotNull String targetEntity,
    
    @JsonProperty("strategy")
    @Nullable String strategy,
    
    @JsonProperty("targetEntityData")
    @Nullable Map<String, Object> targetEntityData
) {
    
    /**
     * Compact constructor with validation.
     */
    public EntityMergeRequest {
        Objects.requireNonNull(sourceEntities, "sourceEntities must not be null");
        Objects.requireNonNull(targetEntity, "targetEntity must not be null");
        
        if (sourceEntities.isEmpty()) {
            throw new IllegalArgumentException("sourceEntities must not be empty");
        }
        
        if (targetEntity.isBlank()) {
            throw new IllegalArgumentException("targetEntity must not be blank");
        }
        
        // Validate each source entity
        for (int i = 0; i < sourceEntities.size(); i++) {
            String entity = sourceEntities.get(i);
            if (entity == null || entity.isBlank()) {
                throw new IllegalArgumentException("sourceEntities[" + i + "] must not be null or blank");
            }
        }
    }
    
    /**
     * Creates a request with required fields only (default strategy and no target data).
     * 
     * @param sourceEntities Entity names to merge
     * @param targetEntity Target entity name
     * @return New EntityMergeRequest
     */
    public static EntityMergeRequest of(
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity
    ) {
        return new EntityMergeRequest(sourceEntities, targetEntity, null, null);
    }
    
    /**
     * Creates a request with a specific strategy.
     * 
     * @param sourceEntities Entity names to merge
     * @param targetEntity Target entity name
     * @param strategy Merge strategy
     * @return New EntityMergeRequest
     */
    public static EntityMergeRequest of(
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity,
        @NotNull MergeStrategy strategy
    ) {
        return new EntityMergeRequest(sourceEntities, targetEntity, strategy.name(), null);
    }
    
    /**
     * Gets the merge strategy, defaulting to CONCATENATE if not specified.
     * 
     * @return The parsed MergeStrategy
     */
    public MergeStrategy getMergeStrategy() {
        return MergeStrategy.fromString(strategy);
    }
    
    /**
     * Checks if this request specifies an LLM-based strategy.
     * 
     * @return true if strategy requires LLM calls
     */
    public boolean requiresLLM() {
        return getMergeStrategy().requiresLLM();
    }
}
