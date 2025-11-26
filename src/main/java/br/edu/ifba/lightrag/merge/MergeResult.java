package br.edu.ifba.lightrag.merge;

import br.edu.ifba.lightrag.core.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Result of an entity merge operation.
 * 
 * <p>Contains the merged target entity and statistics about the merge operation,
 * including the number of redirected relationships, deleted source entities,
 * and deduplicated relations.</p>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * MergeResult result = mergeService.mergeEntities(
 *     projectId,
 *     List.of("AI", "Artificial Intelligence"),
 *     "Artificial Intelligence",
 *     MergeStrategy.CONCATENATE,
 *     Map.of("type", "CONCEPT")
 * );
 * 
 * System.out.println("Merged into: " + result.targetEntity().getEntityName());
 * System.out.println("Relations redirected: " + result.relationsRedirected());
 * System.out.println("Source entities deleted: " + result.sourceEntitiesDeleted());
 * System.out.println("Relations deduplicated: " + result.relationsDeduped());
 * }</pre>
 * 
 * @param targetEntity The merged target entity with combined descriptions and sources
 * @param relationsRedirected Count of relationships redirected to the target entity
 * @param sourceEntitiesDeleted Count of source entities that were deleted after merge
 * @param relationsDeduped Count of duplicate relations that were merged (same src->tgt pair)
 * @since spec-007
 */
public record MergeResult(
    @NotNull Entity targetEntity,
    int relationsRedirected,
    int sourceEntitiesDeleted,
    int relationsDeduped
) {
    
    /**
     * Compact constructor with validation.
     */
    public MergeResult {
        Objects.requireNonNull(targetEntity, "targetEntity must not be null");
        if (relationsRedirected < 0) {
            throw new IllegalArgumentException("relationsRedirected must be >= 0, got: " + relationsRedirected);
        }
        if (sourceEntitiesDeleted < 0) {
            throw new IllegalArgumentException("sourceEntitiesDeleted must be >= 0, got: " + sourceEntitiesDeleted);
        }
        if (relationsDeduped < 0) {
            throw new IllegalArgumentException("relationsDeduped must be >= 0, got: " + relationsDeduped);
        }
    }
    
    /**
     * Creates a MergeResult with zero statistics.
     * 
     * <p>Useful for cases where a merge was a no-op (e.g., single entity,
     * or entity already merged).</p>
     * 
     * @param targetEntity The target entity
     * @return MergeResult with all counts set to 0
     */
    public static MergeResult noOp(@NotNull Entity targetEntity) {
        return new MergeResult(targetEntity, 0, 0, 0);
    }
    
    /**
     * Returns the total number of operations performed.
     * 
     * @return Sum of redirected relations, deleted entities, and deduped relations
     */
    public int totalOperations() {
        return relationsRedirected + sourceEntitiesDeleted + relationsDeduped;
    }
    
    /**
     * Checks if the merge actually changed anything.
     * 
     * @return true if any modifications were made
     */
    public boolean hasChanges() {
        return totalOperations() > 0;
    }
    
    /**
     * Builder for constructing MergeResult instances.
     */
    public static class Builder {
        private Entity targetEntity;
        private int relationsRedirected = 0;
        private int sourceEntitiesDeleted = 0;
        private int relationsDeduped = 0;
        
        public Builder targetEntity(@NotNull Entity targetEntity) {
            this.targetEntity = targetEntity;
            return this;
        }
        
        public Builder relationsRedirected(int relationsRedirected) {
            this.relationsRedirected = relationsRedirected;
            return this;
        }
        
        public Builder sourceEntitiesDeleted(int sourceEntitiesDeleted) {
            this.sourceEntitiesDeleted = sourceEntitiesDeleted;
            return this;
        }
        
        public Builder relationsDeduped(int relationsDeduped) {
            this.relationsDeduped = relationsDeduped;
            return this;
        }
        
        public Builder incrementRelationsRedirected() {
            this.relationsRedirected++;
            return this;
        }
        
        public Builder incrementSourceEntitiesDeleted() {
            this.sourceEntitiesDeleted++;
            return this;
        }
        
        public Builder incrementRelationsDeduped() {
            this.relationsDeduped++;
            return this;
        }
        
        public MergeResult build() {
            return new MergeResult(targetEntity, relationsRedirected, sourceEntitiesDeleted, relationsDeduped);
        }
    }
    
    /**
     * Creates a new builder.
     * 
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
