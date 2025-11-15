package br.edu.ifba.lightrag.core;

import java.util.List;
import java.util.Set;

/**
 * Represents a cluster of entities that should be merged.
 * 
 * A cluster contains:
 * - A canonical entity (the "representative" entity for the cluster)
 * - A list of entity indices that belong to this cluster
 * - Merged descriptions from all entities
 * - Aliases (alternative names) extracted from cluster members
 * 
 * @param canonicalEntity The canonical entity representing this cluster
 * @param entityIndices Indices of entities in the original list that belong to this cluster
 * @param aliases Alternative names for this entity (excluding the canonical name)
 * @param mergedDescription Combined description from all entities in the cluster
 */
public record EntityCluster(
    Entity canonicalEntity,
    Set<Integer> entityIndices,
    List<String> aliases,
    String mergedDescription
) {
    
    /**
     * Creates an EntityCluster with validation.
     */
    public EntityCluster {
        if (canonicalEntity == null) {
            throw new IllegalArgumentException("canonicalEntity cannot be null");
        }
        if (entityIndices == null || entityIndices.isEmpty()) {
            throw new IllegalArgumentException("entityIndices cannot be null or empty");
        }
        if (aliases == null) {
            throw new IllegalArgumentException("aliases cannot be null");
        }
        if (mergedDescription == null || mergedDescription.isBlank()) {
            throw new IllegalArgumentException("mergedDescription cannot be null or blank");
        }
    }
    
    /**
     * Returns the size of the cluster (number of entities merged).
     * 
     * @return Number of entities in this cluster
     */
    public int size() {
        return entityIndices.size();
    }
    
    /**
     * Returns true if this is a singleton cluster (no duplicates found).
     * 
     * @return true if cluster contains only one entity
     */
    public boolean isSingleton() {
        return entityIndices.size() == 1;
    }
    
    /**
     * Returns true if this cluster contains the given entity index.
     * 
     * @param index The entity index to check
     * @return true if index is in this cluster
     */
    public boolean containsEntityIndex(int index) {
        return entityIndices.contains(index);
    }
    
    /**
     * Returns a formatted string representation for logging.
     * 
     * @return Formatted string with cluster information
     */
    public String toLogString() {
        if (isSingleton()) {
            return String.format("Cluster: '%s' (no duplicates)", canonicalEntity.getEntityName());
        } else {
            return String.format(
                "Cluster: '%s' [%d entities merged] Aliases: %s",
                canonicalEntity.getEntityName(),
                size(),
                aliases.isEmpty() ? "none" : String.join(", ", aliases)
            );
        }
    }
}
