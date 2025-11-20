package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory graph storage implementation inspired by NetworkX.
 * Uses adjacency lists for efficient graph operations.
 * Thread-safe with ConcurrentHashMap backing.
 */
public class InMemoryGraphStorage implements GraphStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(InMemoryGraphStorage.class);
    
    // Entity storage: entityName -> Entity
    private final ConcurrentHashMap<String, Entity> entities;
    
    // Adjacency list for outgoing edges: srcId -> (tgtId -> Relation)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Relation>> outgoingEdges;
    
    // Adjacency list for incoming edges: tgtId -> (srcId -> Relation)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Relation>> incomingEdges;
    
    private volatile boolean initialized = false;
    
    public InMemoryGraphStorage() {
        this.entities = new ConcurrentHashMap<>();
        this.outgoingEdges = new ConcurrentHashMap<>();
        this.incomingEdges = new ConcurrentHashMap<>();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!initialized) {
                initialized = true;
                logger.info("InMemoryGraphStorage initialized");
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            entities.put(entity.getEntityName(), entity);
            logger.debug("Upserted entity: {} for project: {}", entity.getEntityName(), projectId);
        });
    }
    
    @Override
    public CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entityList) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            for (Entity entity : entityList) {
                entities.put(entity.getEntityName(), entity);
            }
            logger.debug("Upserted {} entities for project: {}", entityList.size(), projectId);
        });
    }
    
    @Override
    public CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            String srcId = relation.getSrcId();
            String tgtId = relation.getTgtId();
            
            // Add to outgoing edges
            outgoingEdges.computeIfAbsent(srcId, k -> new ConcurrentHashMap<>())
                .put(tgtId, relation);
            
            // Add to incoming edges
            incomingEdges.computeIfAbsent(tgtId, k -> new ConcurrentHashMap<>())
                .put(srcId, relation);
            
            logger.debug("Upserted relation: {} -> {} for project: {}", srcId, tgtId, projectId);
        });
    }
    
    @Override
    public CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations) {
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            for (Relation relation : relations) {
                String srcId = relation.getSrcId();
                String tgtId = relation.getTgtId();
                
                outgoingEdges.computeIfAbsent(srcId, k -> new ConcurrentHashMap<>())
                    .put(tgtId, relation);
                
                incomingEdges.computeIfAbsent(tgtId, k -> new ConcurrentHashMap<>())
                    .put(srcId, relation);
            }
            logger.debug("Upserted {} relations for project: {}", relations.size(), projectId);
        });
    }
    
    @Override
    public CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName) {
        ensureInitialized();
        return CompletableFuture.completedFuture(entities.get(entityName));
    }
    
    @Override
    public CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            List<Entity> result = new ArrayList<>();
            for (String name : entityNames) {
                Entity entity = entities.get(name);
                if (entity != null) {
                    result.add(entity);
                }
            }
            return result;
        });
    }
    
    @Override
    public CompletableFuture<Relation> getRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            ConcurrentHashMap<String, Relation> targets = outgoingEdges.get(srcId);
            if (targets != null) {
                return targets.get(tgtId);
            }
            return null;
        });
    }
    
    @Override
    public CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            Set<Relation> relations = new HashSet<>();
            
            // Get outgoing relations
            ConcurrentHashMap<String, Relation> outgoing = outgoingEdges.get(entityName);
            if (outgoing != null) {
                relations.addAll(outgoing.values());
            }
            
            // Get incoming relations
            ConcurrentHashMap<String, Relation> incoming = incomingEdges.get(entityName);
            if (incoming != null) {
                relations.addAll(incoming.values());
            }
            
            return new ArrayList<>(relations);
        });
    }
    
    @Override
    public CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId) {
        ensureInitialized();
        return CompletableFuture.completedFuture(new ArrayList<>(entities.values()));
    }
    
    @Override
    public CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            Set<Relation> allRelations = new HashSet<>();
            for (ConcurrentHashMap<String, Relation> targets : outgoingEdges.values()) {
                allRelations.addAll(targets.values());
            }
            return new ArrayList<>(allRelations);
        });
    }
    
    @Override
    public CompletableFuture<Boolean> deleteEntity(@NotNull String projectId, @NotNull String entityName) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            boolean existed = entities.remove(entityName) != null;
            
            // Remove all relations involving this entity
            outgoingEdges.remove(entityName);
            incomingEdges.remove(entityName);
            
            // Remove from other entities' adjacency lists
            for (ConcurrentHashMap<String, Relation> targets : outgoingEdges.values()) {
                targets.remove(entityName);
            }
            for (ConcurrentHashMap<String, Relation> sources : incomingEdges.values()) {
                sources.remove(entityName);
            }
            
            if (existed) {
                logger.debug("Deleted entity: {} for project: {}", entityName, projectId);
            }
            return existed;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> deleteRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            boolean existed = false;
            
            ConcurrentHashMap<String, Relation> targets = outgoingEdges.get(srcId);
            if (targets != null) {
                existed = targets.remove(tgtId) != null;
            }
            
            ConcurrentHashMap<String, Relation> sources = incomingEdges.get(tgtId);
            if (sources != null) {
                sources.remove(srcId);
            }
            
            if (existed) {
                logger.debug("Deleted relation: {} -> {} for project: {}", srcId, tgtId, projectId);
            }
            return existed;
        });
    }
    
    @Override
    public CompletableFuture<Integer> deleteBySourceId(@NotNull String projectId, @NotNull String sourceId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            
            // Note: source_id no longer exists, deleteBySourceId is deprecated
            // This method is kept for interface compatibility but performs no operation
            logger.warn("deleteBySourceId is deprecated and no longer supported");
            List<String> entitiesToDelete = List.of();
            
            for (String entityName : entitiesToDelete) {
                deleteEntity(projectId, entityName).join();
                count++;
            }
            
            // Note: source_id no longer exists, deleteBySourceId is deprecated
            List<Relation> relationsToDelete = List.of();
            
            for (Relation relation : relationsToDelete) {
                deleteRelation(projectId, relation.getSrcId(), relation.getTgtId()).join();
                count++;
            }
            
            logger.debug("Deleted {} items for source ID: {} in project: {}", count, sourceId, projectId);
            return count;
        });
    }
    
    @Override
    public CompletableFuture<GraphSubgraph> traverse(@NotNull String projectId, @NotNull String startEntity, int maxDepth) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            Set<String> visitedEntities = new HashSet<>();
            Set<Relation> visitedRelations = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            Map<String, Integer> depths = new HashMap<>();
            
            queue.add(startEntity);
            depths.put(startEntity, 0);
            visitedEntities.add(startEntity);
            
            while (!queue.isEmpty()) {
                String currentEntity = queue.poll();
                int currentDepth = depths.get(currentEntity);
                
                if (currentDepth >= maxDepth) {
                    continue;
                }
                
                // Explore outgoing edges
                ConcurrentHashMap<String, Relation> outgoing = outgoingEdges.get(currentEntity);
                if (outgoing != null) {
                    for (Map.Entry<String, Relation> entry : outgoing.entrySet()) {
                        String neighbor = entry.getKey();
                        Relation relation = entry.getValue();
                        
                        visitedRelations.add(relation);
                        
                        if (!visitedEntities.contains(neighbor)) {
                            visitedEntities.add(neighbor);
                            queue.add(neighbor);
                            depths.put(neighbor, currentDepth + 1);
                        }
                    }
                }
                
                // Explore incoming edges
                ConcurrentHashMap<String, Relation> incoming = incomingEdges.get(currentEntity);
                if (incoming != null) {
                    for (Map.Entry<String, Relation> entry : incoming.entrySet()) {
                        String neighbor = entry.getKey();
                        Relation relation = entry.getValue();
                        
                        visitedRelations.add(relation);
                        
                        if (!visitedEntities.contains(neighbor)) {
                            visitedEntities.add(neighbor);
                            queue.add(neighbor);
                            depths.put(neighbor, currentDepth + 1);
                        }
                    }
                }
            }
            
            // Collect entities
            List<Entity> resultEntities = visitedEntities.stream()
                .map(entities::get)
                .filter(Objects::nonNull)
                .toList();
            
            List<Relation> resultRelations = new ArrayList<>(visitedRelations);
            
            return new GraphSubgraph(resultEntities, resultRelations);
        });
    }
    
    @Override
    public CompletableFuture<List<Entity>> findShortestPath(@NotNull String projectId, @NotNull String sourceEntity, @NotNull String targetEntity) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            if (!entities.containsKey(sourceEntity) || !entities.containsKey(targetEntity)) {
                return List.of();
            }
            
            // BFS for shortest path
            Queue<String> queue = new LinkedList<>();
            Map<String, String> parent = new HashMap<>();
            Set<String> visited = new HashSet<>();
            
            queue.add(sourceEntity);
            visited.add(sourceEntity);
            parent.put(sourceEntity, null);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                
                if (current.equals(targetEntity)) {
                    // Reconstruct path
                    List<String> path = new ArrayList<>();
                    String node = targetEntity;
                    while (node != null) {
                        path.add(0, node);
                        node = parent.get(node);
                    }
                    
                    // Convert to entities
                    return path.stream()
                        .map(entities::get)
                        .filter(Objects::nonNull)
                        .toList();
                }
                
                // Explore neighbors (both outgoing and incoming)
                Set<String> neighbors = new HashSet<>();
                
                ConcurrentHashMap<String, Relation> outgoing = outgoingEdges.get(current);
                if (outgoing != null) {
                    neighbors.addAll(outgoing.keySet());
                }
                
                ConcurrentHashMap<String, Relation> incoming = incomingEdges.get(current);
                if (incoming != null) {
                    neighbors.addAll(incoming.keySet());
                }
                
                for (String neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        parent.put(neighbor, current);
                        queue.add(neighbor);
                    }
                }
            }
            
            // No path found
            return List.of();
        });
    }
    
    @Override
    public CompletableFuture<Void> createProjectGraph(@NotNull String projectId) {
        // In-memory implementation: single shared graph for all projects
        // This is a test/stub implementation - projectId is ignored
        ensureInitialized();
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId) {
        // In-memory implementation: clear all data (no per-project isolation in memory)
        ensureInitialized();
        return CompletableFuture.runAsync(() -> {
            entities.clear();
            outgoingEdges.clear();
            incomingEdges.clear();
            logger.info("Cleared graph data for project: {}", projectId);
        });
    }
    
    @Override
    public CompletableFuture<Boolean> graphExists(@NotNull String projectId) {
        // In-memory implementation: always returns true if initialized
        return CompletableFuture.completedFuture(initialized);
    }
    
    @Override
    public CompletableFuture<GraphStats> getStats(@NotNull String projectId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            long entityCount = entities.size();
            long relationCount = outgoingEdges.values().stream()
                .mapToLong(Map::size)
                .sum();
            
            double averageDegree = entityCount > 0 ? (double) relationCount / entityCount : 0.0;
            
            return new GraphStats(entityCount, relationCount, averageDegree);
        });
    }
    
    @Override
    public void close() throws Exception {
        if (initialized) {
            entities.clear();
            outgoingEdges.clear();
            incomingEdges.clear();
            initialized = false;
            logger.info("InMemoryGraphStorage closed");
        }
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Storage not initialized. Call initialize() first.");
        }
    }
}
