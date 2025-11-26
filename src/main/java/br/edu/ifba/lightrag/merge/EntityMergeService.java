package br.edu.ifba.lightrag.merge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Service interface for entity merge operations with relationship management.
 * 
 * <p>Handles merging multiple entities into a single target entity, including:
 * <ul>
 *   <li>Redirecting all relationships to the target entity</li>
 *   <li>Preventing self-loop relationships after merge</li>
 *   <li>Deduplicating relationships (same src→tgt pair)</li>
 *   <li>Applying configured description merge strategy</li>
 *   <li>Deleting source entities and their embeddings</li>
 * </ul>
 * 
 * <h2>Contract:</h2>
 * <ul>
 *   <li>MUST validate all source entities exist</li>
 *   <li>MUST redirect all relationships to target entity</li>
 *   <li>MUST prevent self-loop relationships after merge</li>
 *   <li>MUST deduplicate relationships (same src→tgt pair)</li>
 *   <li>MUST apply configured description merge strategy</li>
 *   <li>MUST delete source entities and embeddings after merge</li>
 *   <li>MUST be transactional (all-or-nothing)</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @Inject
 * EntityMergeService mergeService;
 * 
 * // Merge duplicate AI entities
 * MergeResult result = mergeService.mergeEntities(
 *     projectId,
 *     List.of("AI", "Artificial Intelligence", "Machine Intelligence"),
 *     "Artificial Intelligence",
 *     MergeStrategy.LLM_SUMMARIZE,
 *     Map.of("type", "CONCEPT")
 * );
 * 
 * // Check results
 * System.out.println("Relations redirected: " + result.relationsRedirected());
 * System.out.println("Entities merged: " + result.sourceEntitiesDeleted());
 * }</pre>
 * 
 * @see MergeStrategy
 * @see MergeResult
 * @since spec-007
 */
public interface EntityMergeService {
    
    /**
     * Merges multiple entities into a single target entity.
     * 
     * <p>The merge process:</p>
     * <ol>
     *   <li>Validates all source entities exist in the project</li>
     *   <li>Collects all relationships involving source entities</li>
     *   <li>Redirects relationships to the target entity</li>
     *   <li>Filters out self-loop relationships (src == tgt)</li>
     *   <li>Deduplicates relationships with same src→tgt pair</li>
     *   <li>Merges descriptions using the specified strategy</li>
     *   <li>Creates or updates the target entity</li>
     *   <li>Saves redirected relationships</li>
     *   <li>Deletes source entities and their embeddings</li>
     * </ol>
     * 
     * @param projectId UUID of the project containing the entities (required)
     * @param sourceEntities List of entity names to merge (required, non-empty)
     * @param targetEntity Name of the target entity to merge into (required)
     * @param strategy Strategy for merging descriptions (default: CONCATENATE)
     * @param targetEntityData Optional data to set on target entity (e.g., "type": "CONCEPT")
     * @return Result containing the merged entity and operation statistics
     * @throws IllegalArgumentException if:
     *         <ul>
     *           <li>projectId is null or invalid</li>
     *           <li>sourceEntities is null or empty</li>
     *           <li>targetEntity is null or blank</li>
     *           <li>any source entity doesn't exist in the project</li>
     *         </ul>
     * @throws IllegalStateException if a circular merge is detected or conflict occurs
     */
    MergeResult mergeEntities(
        @NotNull String projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity,
        @NotNull MergeStrategy strategy,
        @Nullable Map<String, Object> targetEntityData
    );
    
    /**
     * Merges entities using the default strategy (CONCATENATE).
     * 
     * <p>Convenience method that calls {@link #mergeEntities} with
     * {@link MergeStrategy#CONCATENATE} and no target entity data.</p>
     * 
     * @param projectId UUID of the project containing the entities
     * @param sourceEntities List of entity names to merge
     * @param targetEntity Name of the target entity to merge into
     * @return Result containing the merged entity and operation statistics
     * @throws IllegalArgumentException if validation fails
     * @throws IllegalStateException if merge conflict occurs
     */
    default MergeResult mergeEntities(
        @NotNull String projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity
    ) {
        return mergeEntities(projectId, sourceEntities, targetEntity, MergeStrategy.defaultStrategy(), null);
    }
    
    /**
     * Validates that a merge operation can be performed.
     * 
     * <p>Checks without actually performing the merge. Useful for pre-validation
     * in REST endpoints before committing to a potentially expensive operation.</p>
     * 
     * @param projectId UUID of the project
     * @param sourceEntities List of entity names to validate
     * @param targetEntity Name of the target entity
     * @return List of validation errors, empty if validation passes
     */
    List<String> validateMerge(
        @NotNull String projectId,
        @NotNull List<String> sourceEntities,
        @NotNull String targetEntity
    );
    
    /**
     * Checks if an entity exists in the project.
     * 
     * @param projectId UUID of the project
     * @param entityName Name of the entity to check
     * @return true if entity exists, false otherwise
     */
    boolean entityExists(@NotNull String projectId, @NotNull String entityName);
}
