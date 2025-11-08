package br.edu.ifba.lightrag.storage;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for graph storage operations.
 * Used for storing and querying the knowledge graph of entities and relations.
 * 
 * Implementations: NetworkXStorage, Neo4JStorage, PGGraphStorage, AGEStorage, MemgraphStorage
 */
public interface GraphStorage extends AutoCloseable {
    
    /**
     * Initializes the graph storage backend.
     * Must be called before any other operations.
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Adds or updates an entity node in the graph.
     *
     * @param entity the entity to upsert
     */
    CompletableFuture<Void> upsertEntity(@NotNull Entity entity);
    
    /**
     * Adds or updates multiple entity nodes in the graph.
     *
     * @param entities the entities to upsert
     */
    CompletableFuture<Void> upsertEntities(@NotNull List<Entity> entities);
    
    /**
     * Adds or updates a relation edge in the graph.
     *
     * @param relation the relation to upsert
     */
    CompletableFuture<Void> upsertRelation(@NotNull Relation relation);
    
    /**
     * Adds or updates multiple relation edges in the graph.
     *
     * @param relations the relations to upsert
     */
    CompletableFuture<Void> upsertRelations(@NotNull List<Relation> relations);
    
    /**
     * Gets an entity by its name.
     *
     * @param entityName the name of the entity
     * @return the entity, or null if not found
     */
    CompletableFuture<Entity> getEntity(@NotNull String entityName);
    
    /**
     * Gets multiple entities by their names.
     *
     * @param entityNames the names of the entities
     * @return a list of found entities
     */
    CompletableFuture<List<Entity>> getEntities(@NotNull List<String> entityNames);
    
    /**
     * Gets a relation between two entities.
     *
     * @param srcId the source entity ID
     * @param tgtId the target entity ID
     * @return the relation, or null if not found
     */
    CompletableFuture<Relation> getRelation(@NotNull String srcId, @NotNull String tgtId);
    
    /**
     * Gets all relations for a specific entity.
     *
     * @param entityName the entity name
     * @return a list of relations where the entity is either source or target
     */
    CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String entityName);
    
    /**
     * Gets all entities in the graph.
     *
     * @return a list of all entities
     */
    CompletableFuture<List<Entity>> getAllEntities();
    
    /**
     * Gets all relations in the graph.
     *
     * @return a list of all relations
     */
    CompletableFuture<List<Relation>> getAllRelations();
    
    /**
     * Deletes an entity from the graph.
     *
     * @param entityName the name of the entity to delete
     * @return true if the entity was deleted, false if it didn't exist
     */
    CompletableFuture<Boolean> deleteEntity(@NotNull String entityName);
    
    /**
     * Deletes a relation from the graph.
     *
     * @param srcId the source entity ID
     * @param tgtId the target entity ID
     * @return true if the relation was deleted, false if it didn't exist
     */
    CompletableFuture<Boolean> deleteRelation(@NotNull String srcId, @NotNull String tgtId);
    
    /**
     * Deletes all entities and relations associated with a source document.
     *
     * @param sourceId the source document ID
     * @return the number of entities and relations deleted
     */
    CompletableFuture<Integer> deleteBySourceId(@NotNull String sourceId);
    
    /**
     * Performs a graph traversal query starting from an entity.
     *
     * @param startEntity the entity to start from
     * @param maxDepth the maximum depth to traverse
     * @return a subgraph containing entities and relations
     */
    CompletableFuture<GraphSubgraph> traverse(@NotNull String startEntity, int maxDepth);
    
    /**
     * Finds the shortest path between two entities.
     *
     * @param sourceEntity the source entity
     * @param targetEntity the target entity
     * @return a list of entities representing the path, or empty if no path exists
     */
    CompletableFuture<List<Entity>> findShortestPath(@NotNull String sourceEntity, @NotNull String targetEntity);
    
    /**
     * Clears all data in the graph.
     */
    CompletableFuture<Void> clear();
    
    /**
     * Gets statistics about the graph.
     *
     * @return graph statistics including entity and relation counts
     */
    CompletableFuture<GraphStats> getStats();
    
    /**
     * Closes the storage and releases resources.
     */
    @Override
    void close() throws Exception;
    
    /**
     * Represents a subgraph containing entities and relations.
     */
    record GraphSubgraph(
            @NotNull List<Entity> entities,
            @NotNull List<Relation> relations) {
    }
    
    /**
     * Represents statistics about the graph.
     */
    record GraphStats(
            long entityCount,
            long relationCount,
            double averageDegree) {
    }
}
