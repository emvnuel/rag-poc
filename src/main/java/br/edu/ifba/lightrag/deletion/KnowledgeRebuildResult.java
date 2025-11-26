package br.edu.ifba.lightrag.deletion;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Result of document deletion with knowledge graph rebuild.
 * 
 * <p>Contains detailed information about what was deleted and rebuilt during
 * document deletion, enabling audit trails and debugging.
 * 
 * @param documentId The deleted document ID
 * @param entitiesDeleted Entity names that were fully removed (no remaining sources)
 * @param entitiesRebuilt Entity names that had descriptions rebuilt from remaining sources
 * @param relationsDeleted Count of relations that were fully removed
 * @param relationsRebuilt Count of relations that had descriptions rebuilt
 * @param errors Any errors encountered during rebuild (operation continues despite errors)
 * @since spec-007
 */
public record KnowledgeRebuildResult(
    @NotNull UUID documentId,
    @NotNull Set<String> entitiesDeleted,
    @NotNull Set<String> entitiesRebuilt,
    int relationsDeleted,
    int relationsRebuilt,
    @NotNull List<String> errors
) {
    
    /**
     * Creates a new KnowledgeRebuildResult with validation.
     */
    public KnowledgeRebuildResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(entitiesDeleted, "entitiesDeleted must not be null");
        Objects.requireNonNull(entitiesRebuilt, "entitiesRebuilt must not be null");
        Objects.requireNonNull(errors, "errors must not be null");
        
        if (relationsDeleted < 0) {
            throw new IllegalArgumentException("relationsDeleted must be >= 0");
        }
        if (relationsRebuilt < 0) {
            throw new IllegalArgumentException("relationsRebuilt must be >= 0");
        }
    }
    
    /**
     * Checks if the deletion had any errors.
     *
     * @return true if there were errors during rebuild
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Gets the total count of entities affected (deleted + rebuilt).
     *
     * @return total affected entity count
     */
    public int totalEntitiesAffected() {
        return entitiesDeleted.size() + entitiesRebuilt.size();
    }
    
    /**
     * Gets the total count of relations affected (deleted + rebuilt).
     *
     * @return total affected relation count
     */
    public int totalRelationsAffected() {
        return relationsDeleted + relationsRebuilt;
    }
    
    /**
     * Checks if any knowledge graph changes were made.
     *
     * @return true if any entities or relations were affected
     */
    public boolean hasChanges() {
        return totalEntitiesAffected() > 0 || totalRelationsAffected() > 0;
    }
}
