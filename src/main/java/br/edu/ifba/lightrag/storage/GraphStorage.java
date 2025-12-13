package br.edu.ifba.lightrag.storage;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for graph storage operations with per-project isolation.
 * Used for storing and querying the knowledge graph of entities and relations.
 * 
 * Each project has its own isolated graph (e.g., graph_<project_uuid>).
 * All operations require a projectId to route to the correct graph.
 * 
 * Implementations: AgeGraphStorage, InMemoryGraphStorage
 * 
 * @version 2.0.0 - Updated for project-level graph isolation
 */
public interface GraphStorage extends AutoCloseable {
    
    /**
     * Initializes the graph storage backend.
     * Must be called before any other operations.
     */
    CompletableFuture<Void> initialize();
    
    // ===== Graph Lifecycle Methods =====
    
    /**
     * Creates a new isolated graph for a project.
     * 
     * The graph name is derived from the projectId using the convention:
     * graph_<uuid_prefix> where uuid_prefix is the first 32 chars of the UUID without hyphens.
     * 
     * This operation is idempotent - if the graph already exists, it returns successfully.
     * 
     * @param projectId the project UUID (must be valid UUID v7 format)
     * @return a CompletableFuture that completes when the graph is created
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     */
    CompletableFuture<Void> createProjectGraph(@NotNull String projectId);
    
    /**
     * Deletes a project's graph and all associated data.
     * 
     * This operation cascades to remove all entities and relations within the graph.
     * If the graph doesn't exist, this operation completes successfully (idempotent).
     * 
     * @param projectId the project UUID
     * @return a CompletableFuture that completes when the graph is deleted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     */
    CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId);
    
    /**
     * Checks if a graph exists for a project.
     * 
     * @param projectId the project UUID
     * @return a CompletableFuture<Boolean> - true if graph exists, false otherwise
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     */
    CompletableFuture<Boolean> graphExists(@NotNull String projectId);
    
    // ===== Entity Operations =====
    
    /**
     * Adds or updates an entity node in the project's graph.
     * 
     * Uses MERGE semantics for deduplication within the project.
     * Entities with the same name in different projects are separate nodes.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entity the entity to upsert
     * @return a CompletableFuture that completes when the entity is upserted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity);
    
    /**
     * Adds or updates multiple entity nodes in the project's graph.
     * 
     * Recommended batch size: 1000 entities for optimal performance.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entities the entities to upsert
     * @return a CompletableFuture that completes when all entities are upserted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entities);
    
    // ===== Relation Operations =====
    
    /**
     * Adds or updates a relation edge in the project's graph.
     * 
     * Relations only connect entities within the same project graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param relation the relation to upsert
     * @return a CompletableFuture that completes when the relation is upserted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation);
    
    /**
     * Adds or updates multiple relation edges in the project's graph.
     * 
     * Recommended batch size: 1000 relations for optimal performance.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param relations the relations to upsert
     * @return a CompletableFuture that completes when all relations are upserted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations);
    
    // ===== Query Operations =====
    
    /**
     * Gets an entity by its name from the project's graph.
     * 
     * Only searches within the specified project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entityName the name of the entity
     * @return a CompletableFuture<Entity> - the entity, or null if not found
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName);
    
    /**
     * Gets multiple entities by their names from the project's graph.
     * 
     * Only searches within the specified project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entityNames the names of the entities
     * @return a CompletableFuture<List<Entity>> - found entities (may be partial)
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames);
    
    /**
     * Gets a relation between two entities from the project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param srcId the source entity ID
     * @param tgtId the target entity ID
     * @return a CompletableFuture<Relation> - the relation, or null if not found
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Relation> getRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId);
    
    /**
     * Gets all relations for a specific entity from the project's graph.
     * 
     * Only returns relations within the specified project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entityName the entity name
     * @return a CompletableFuture<List<Relation>> - relations where entity is source or target
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName);
    
    /**
     * Gets all entities from the project's graph.
     * 
     * Only returns entities from the specified project (no cross-project leakage).
     *
     * @param projectId the project UUID (routes to project's graph)
     * @return a CompletableFuture<List<Entity>> - all entities in project
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId);
    
    /**
     * Gets all relations from the project's graph.
     * 
     * Only returns relations from the specified project (no cross-project leakage).
     *
     * @param projectId the project UUID (routes to project's graph)
     * @return a CompletableFuture<List<Relation>> - all relations in project
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId);
    
    // ===== Batch Query Operations (spec-007) =====
    
    /**
     * Gets entities that have the specified source chunks in their sourceIds.
     * 
     * Used for document deletion to identify affected entities.
     *
     * @param projectId the project UUID
     * @param chunkIds the chunk IDs to search for
     * @return a CompletableFuture<List<Entity>> - entities sourced from any of the chunks
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<List<Entity>> getEntitiesBySourceChunks(@NotNull String projectId, @NotNull List<String> chunkIds);
    
    /**
     * Gets relations that have the specified source chunks in their sourceIds.
     * 
     * Used for document deletion to identify affected relations.
     *
     * @param projectId the project UUID
     * @param chunkIds the chunk IDs to search for
     * @return a CompletableFuture<List<Relation>> - relations sourced from any of the chunks
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<List<Relation>> getRelationsBySourceChunks(@NotNull String projectId, @NotNull List<String> chunkIds);
    
    /**
     * Gets entities in batches for streaming export.
     * 
     * Supports pagination for memory-efficient export of large graphs.
     *
     * @param projectId the project UUID
     * @param offset the number of entities to skip
     * @param limit the maximum number of entities to return
     * @return a CompletableFuture<List<Entity>> - batch of entities
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<List<Entity>> getEntitiesBatch(@NotNull String projectId, int offset, int limit);
    
    /**
     * Gets relations in batches for streaming export.
     * 
     * Supports pagination for memory-efficient export of large graphs.
     *
     * @param projectId the project UUID
     * @param offset the number of relations to skip
     * @param limit the maximum number of relations to return
     * @return a CompletableFuture<List<Relation>> - batch of relations
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<List<Relation>> getRelationsBatch(@NotNull String projectId, int offset, int limit);
    
    // ===== Delete Operations =====
    
    /**
     * Deletes an entity from the project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param entityName the name of the entity to delete
     * @return a CompletableFuture<Boolean> - true if deleted, false if didn't exist
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Boolean> deleteEntity(@NotNull String projectId, @NotNull String entityName);
    
    /**
     * Deletes a relation from the project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param srcId the source entity ID
     * @param tgtId the target entity ID
     * @return a CompletableFuture<Boolean> - true if deleted, false if didn't exist
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Boolean> deleteRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId);
    
    /**
     * Deletes all entities and relations associated with a source document.
     * 
     * The sourceId typically corresponds to a document UUID.
     * This operation is scoped to the project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param sourceId the source document ID
     * @return a CompletableFuture<Integer> - the number of entities and relations deleted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<Integer> deleteBySourceId(@NotNull String projectId, @NotNull String sourceId);
    
    /**
     * Batch deletes multiple entities by name.
     * 
     * Used for document deletion and entity merge cleanup.
     *
     * @param projectId the project UUID
     * @param entityNames set of entity names to delete
     * @return a CompletableFuture<Integer> - number of entities deleted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<Integer> deleteEntities(@NotNull String projectId, @NotNull java.util.Set<String> entityNames);
    
    /**
     * Batch deletes multiple relations by their src-tgt keys.
     * 
     * Used for document deletion and entity merge cleanup.
     *
     * @param projectId the project UUID
     * @param relationKeys set of relation keys in "source->target" format
     * @return a CompletableFuture<Integer> - number of relations deleted
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<Integer> deleteRelations(@NotNull String projectId, @NotNull java.util.Set<String> relationKeys);
    
    // ===== Update Operations (spec-007) =====
    
    /**
     * Updates an entity's description and source IDs.
     * 
     * Used during document deletion to rebuild entities with remaining sources.
     *
     * @param projectId the project UUID
     * @param entityName the entity to update
     * @param description the new description
     * @param sourceIds the updated set of source chunk IDs
     * @return a CompletableFuture that completes when the update is done
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since spec-007
     */
    CompletableFuture<Void> updateEntityDescription(@NotNull String projectId, @NotNull String entityName, @NotNull String description, @NotNull java.util.Set<String> sourceIds);
    
    // ===== Traversal Operations =====
    
    /**
     * Performs a graph traversal query starting from an entity.
     * 
     * Traversal is scoped to the project's graph (no cross-project edges).
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param startEntity the entity to start from
     * @param maxDepth the maximum depth to traverse
     * @return a CompletableFuture<GraphSubgraph> - subgraph containing entities and relations
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<GraphSubgraph> traverse(@NotNull String projectId, @NotNull String startEntity, int maxDepth);
    
    /**
     * Performs a BFS (Breadth-First Search) graph traversal with node limits.
     * 
     * This method provides better control over graph exploration for large graphs:
     * - Level-by-level traversal ensures breadth-first ordering
     * - maxNodes parameter prevents memory exhaustion on large graphs
     * - Batch neighbor queries improve performance over single-node queries
     * 
     * Based on the official LightRAG Python implementation's _bfs_subgraph method.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param startEntity the entity to start from
     * @param maxDepth the maximum depth to traverse (0 = start node only)
     * @param maxNodes the maximum number of nodes to return (0 = unlimited)
     * @return a CompletableFuture<GraphSubgraph> - subgraph containing entities and relations
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     * @since implementation-comparison
     */
    CompletableFuture<GraphSubgraph> traverseBFS(
            @NotNull String projectId, 
            @NotNull String startEntity, 
            int maxDepth, 
            int maxNodes);
    
    /**
     * Finds the shortest path between two entities within the project's graph.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @param sourceEntity the source entity
     * @param targetEntity the target entity
     * @return a CompletableFuture<List<Entity>> - entities in the path, or empty if no path
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<List<Entity>> findShortestPath(@NotNull String projectId, @NotNull String sourceEntity, @NotNull String targetEntity);
    
    // ===== Batch Operations for Performance =====
    
    /**
     * Gets the degree (number of connections) for multiple entities in a single batch.
     * 
     * This is more efficient than calling getRelationsForEntity() for each entity
     * when you only need the count, not the actual relations.
     *
     * @param projectId the project UUID
     * @param entityNames list of entity names to get degrees for
     * @param batchSize number of entities to process per database query (recommended: 500)
     * @return a CompletableFuture<Map<String, Integer>> mapping entity names to their degrees
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since implementation-comparison
     */
    CompletableFuture<java.util.Map<String, Integer>> getNodeDegreesBatch(
            @NotNull String projectId, 
            @NotNull List<String> entityNames, 
            int batchSize);
    
    /**
     * Gets multiple entities by name with efficient batching.
     * 
     * This method processes entities in batches to avoid SQL query size limits
     * and improve performance for large entity lists.
     *
     * @param projectId the project UUID
     * @param entityNames list of entity names to retrieve
     * @param batchSize number of entities to process per database query (recommended: 1000)
     * @return a CompletableFuture<Map<String, Entity>> mapping entity names to entities
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @since implementation-comparison
     */
    CompletableFuture<java.util.Map<String, Entity>> getEntitiesMapBatch(
            @NotNull String projectId, 
            @NotNull List<String> entityNames, 
            int batchSize);
    
    // ===== Statistics Operations =====
    
    /**
     * Gets statistics about the project's graph.
     * 
     * Statistics are scoped to the specified project only.
     *
     * @param projectId the project UUID (routes to project's graph)
     * @return a CompletableFuture<GraphStats> - statistics including entity/relation counts
     * @throws IllegalArgumentException if projectId is null or invalid UUID format
     * @throws IllegalStateException if graph doesn't exist for project
     */
    CompletableFuture<GraphStats> getStats(@NotNull String projectId);
    
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
