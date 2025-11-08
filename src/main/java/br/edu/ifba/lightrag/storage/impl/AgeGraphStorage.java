package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apache AGE (A Graph Extension) implementation of GraphStorage.
 * Uses PostgreSQL with the AGE extension for graph operations.
 * Adapted for Quarkus with CDI.
 * 
 * AGE uses Cypher-like query language and stores graphs in PostgreSQL.
 */
@ApplicationScoped
public class AgeGraphStorage implements GraphStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(AgeGraphStorage.class);
    
    @Inject
    AgeConfig config;
    
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    
    /**
     * Default constructor for CDI.
     */
    public AgeGraphStorage() {
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Initializing Apache AGE graph storage");
                config.initialize();
                logger.info("AGE graph storage initialized successfully");
            } catch (SQLException e) {
                logger.error("Failed to initialize AGE graph storage", e);
                throw new RuntimeException("Failed to initialize AGE graph storage", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> upsertEntity(@NotNull Entity entity) {
        return CompletableFuture.runAsync(() -> {
            String cypher = String.format(
                "MERGE (e:Entity {name: '%s'}) " +
                "SET e.entity_type = '%s', e.description = '%s', e.source_id = '%s'",
                escapeCypher(entity.getEntityName()),
                escapeCypher(entity.getEntityType()),
                escapeCypher(entity.getDescription()),
                escapeCypher(entity.getSourceId())
            );
            
            executeCypher(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> upsertEntities(@NotNull List<Entity> entities) {
        if (entities.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                for (Entity entity : entities) {
                    String cypher = String.format(
                        "MERGE (e:Entity {name: '%s'}) " +
                        "SET e.entity_type = '%s', e.description = '%s', e.source_id = '%s'",
                        escapeCypher(entity.getEntityName()),
                        escapeCypher(entity.getEntityType()),
                        escapeCypher(entity.getDescription()),
                        escapeCypher(entity.getSourceId())
                    );
                    
                    executeCypherWithConnection(conn, cypher);
                }
                
                conn.commit();
                logger.debug("Upserted {} entities", entities.size());
                
            } catch (SQLException e) {
                logger.error("Failed to upsert entities", e);
                throw new RuntimeException("Failed to upsert entities", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> upsertRelation(@NotNull Relation relation) {
        return CompletableFuture.runAsync(() -> {
            // Combine entity creation and relation creation in a single query
            String cypher = String.format(Locale.US,
                "MERGE (src:Entity {name: '%s'}) " +
                "MERGE (tgt:Entity {name: '%s'}) " +
                "MERGE (src)-[r:RELATED_TO]->(tgt) " +
                "SET r.description = '%s', r.keywords = '%s', r.weight = %f, r.source_id = '%s'",
                escapeCypher(relation.getSrcId()),
                escapeCypher(relation.getTgtId()),
                escapeCypher(relation.getDescription()),
                escapeCypher(relation.getKeywords()),
                relation.getWeight(),
                escapeCypher(relation.getSourceId())
            );
            
            executeCypher(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> upsertRelations(@NotNull List<Relation> relations) {
        if (relations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                for (Relation relation : relations) {
                    // Combine entity creation and relation creation in a single query
                    String cypher = String.format(Locale.US,
                        "MERGE (src:Entity {name: '%s'}) " +
                        "MERGE (tgt:Entity {name: '%s'}) " +
                        "MERGE (src)-[r:RELATED_TO]->(tgt) " +
                        "SET r.description = '%s', r.keywords = '%s', r.weight = %f, r.source_id = '%s'",
                        escapeCypher(relation.getSrcId()),
                        escapeCypher(relation.getTgtId()),
                        escapeCypher(relation.getDescription()),
                        escapeCypher(relation.getKeywords()),
                        relation.getWeight(),
                        escapeCypher(relation.getSourceId())
                    );
                    executeCypherWithConnection(conn, cypher);
                }
                
                conn.commit();
                logger.debug("Upserted {} relations", relations.size());
                
            } catch (SQLException e) {
                logger.error("Failed to upsert relations", e);
                throw new RuntimeException("Failed to upsert relations", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Entity> getEntity(@NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'}) RETURN e",
                escapeCypher(entityName)
            );
            
            List<Entity> results = queryCypherForEntities(cypher);
            return results.isEmpty() ? null : results.get(0);
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Entity>> getEntities(@NotNull List<String> entityNames) {
        if (entityNames.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            String namesClause = entityNames.stream()
                .map(name -> "'" + escapeCypher(name) + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            
            String cypher = String.format(
                "MATCH (e:Entity) WHERE e.name IN [%s] RETURN e",
                namesClause
            );
            
            return queryCypherForEntities(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<Relation> getRelation(@NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH (src:Entity {name: '%s'})-[r:RELATED_TO]->(tgt:Entity {name: '%s'}) " +
                "RETURN src.name, tgt.name, r",
                escapeCypher(srcId),
                escapeCypher(tgtId)
            );
            
            List<Relation> results = queryCypherForRelations(cypher);
            return results.isEmpty() ? null : results.get(0);
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'})-[r:RELATED_TO]-(other:Entity) " +
                "RETURN e.name, other.name, r",
                escapeCypher(entityName)
            );
            
            return queryCypherForRelations(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Entity>> getAllEntities() {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = "MATCH (e:Entity) RETURN e";
            return queryCypherForEntities(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Relation>> getAllRelations() {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = "MATCH (src:Entity)-[r:RELATED_TO]->(tgt:Entity) RETURN src.name, tgt.name, r";
            return queryCypherForRelations(cypher);
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> deleteEntity(@NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'}) DETACH DELETE e",
                escapeCypher(entityName)
            );
            
            try {
                executeCypher(cypher);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to delete entity: {}", entityName, e);
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> deleteRelation(@NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH (src:Entity {name: '%s'})-[r:RELATED_TO]->(tgt:Entity {name: '%s'}) DELETE r",
                escapeCypher(srcId),
                escapeCypher(tgtId)
            );
            
            try {
                executeCypher(cypher);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to delete relation: {} -> {}", srcId, tgtId, e);
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Integer> deleteBySourceId(@NotNull String sourceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Delete relations by source_id
                String deleteRelations = String.format(
                    "MATCH ()-[r:RELATED_TO {source_id: '%s'}]-() DELETE r",
                    escapeCypher(sourceId)
                );
                executeCypher(deleteRelations);
                
                // Delete entities by source_id
                String deleteEntities = String.format(
                    "MATCH (e:Entity {source_id: '%s'}) DETACH DELETE e",
                    escapeCypher(sourceId)
                );
                executeCypher(deleteEntities);
                
                // Count is approximate since AGE doesn't return counts easily
                return 0;
            } catch (Exception e) {
                logger.error("Failed to delete by source_id: {}", sourceId, e);
                return 0;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<GraphSubgraph> traverse(@NotNull String startEntity, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Entity> entities = new ArrayList<>();
                List<Relation> relations = new ArrayList<>();
                
                // Get all entities within maxDepth hops from start entity
                String entitiesCypher = String.format(
                    "MATCH (start:Entity {name: '%s'})-[*0..%d]-(e:Entity) RETURN DISTINCT e",
                    escapeCypher(startEntity),
                    maxDepth
                );
                entities = queryCypherForEntities(entitiesCypher);
                
                // Get all relations between entities within maxDepth
                String relationsCypher = String.format(
                    "MATCH (start:Entity {name: '%s'})-[*0..%d]-(e:Entity)-[r:RELATED_TO]-(other:Entity) " +
                    "RETURN DISTINCT e.name, other.name, r",
                    escapeCypher(startEntity),
                    maxDepth
                );
                relations = queryCypherForRelations(relationsCypher);
                
                return new GraphSubgraph(entities, relations);
                
            } catch (Exception e) {
                logger.error("Failed to traverse from entity: {}", startEntity, e);
                return new GraphSubgraph(List.of(), List.of());
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Entity>> findShortestPath(@NotNull String sourceEntity, @NotNull String targetEntity) {
        return CompletableFuture.supplyAsync(() -> {
            String cypher = String.format(
                "MATCH path = shortestPath((src:Entity {name: '%s'})-[*]-(tgt:Entity {name: '%s'})) " +
                "RETURN nodes(path)",
                escapeCypher(sourceEntity),
                escapeCypher(targetEntity)
            );
            
            try {
                return queryCypherForEntities(cypher);
            } catch (Exception e) {
                logger.warn("Failed to find shortest path: {} -> {}", sourceEntity, targetEntity, e);
                return List.of();
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            String cypher = "MATCH (n) DETACH DELETE n";
            executeCypher(cypher);
            logger.info("Cleared all data from graph");
        }, executor);
    }
    
    @Override
    public CompletableFuture<GraphStats> getStats() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Count entities
                String entityCountCypher = "MATCH (e:Entity) RETURN count(e) AS count";
                long entityCount = queryCypherForCount(entityCountCypher);
                
                // Count relations
                String relationCountCypher = "MATCH ()-[r:RELATED_TO]->() RETURN count(r) AS count";
                long relationCount = queryCypherForCount(relationCountCypher);
                
                // Calculate average degree
                double avgDegree = entityCount > 0 ? (2.0 * relationCount / entityCount) : 0.0;
                
                return new GraphStats(entityCount, relationCount, avgDegree);
                
            } catch (Exception e) {
                logger.error("Failed to get graph stats", e);
                return new GraphStats(0, 0, 0.0);
            }
        }, executor);
    }
    
    @Override
    public void close() throws Exception {
        executor.shutdown();
        config.close();
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Executes a Cypher query using AGE's cypher() function.
     */
    private void executeCypher(String cypher) {
        try (Connection conn = config.getConnection()) {
            logger.debug("Executing Cypher query: {}", cypher);
            executeCypherWithConnection(conn, cypher);
        } catch (SQLException e) {
            logger.error("Failed to execute Cypher query: {}", cypher, e);
            throw new RuntimeException("Failed to execute Cypher query", e);
        }
    }
    
    /**
     * Executes a Cypher query with an existing connection.
     */
    private void executeCypherWithConnection(Connection conn, String cypher) throws SQLException {
        String sql = String.format(
            "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result agtype)",
            config.getGraphName(),
            cypher
        );
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            stmt.execute(sql);
        }
    }
    
    /**
     * Queries Cypher and parses Entity results.
     */
    private List<Entity> queryCypherForEntities(String cypher) {
        List<Entity> entities = new ArrayList<>();
        
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (e agtype)",
                config.getGraphName(),
                cypher
            );
            
            ResultSet rs = stmt.executeQuery(sql);
            
            while (rs.next()) {
                String agtypeJson = rs.getString(1);
                Entity entity = parseEntityFromAgtype(agtypeJson);
                if (entity != null) {
                    entities.add(entity);
                }
            }
            
        } catch (SQLException e) {
            logger.error("Failed to query entities: {}", cypher, e);
        }
        
        return entities;
    }
    
    /**
     * Queries Cypher and parses Relation results.
     */
    private List<Relation> queryCypherForRelations(String cypher) {
        List<Relation> relations = new ArrayList<>();
        
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (src_name agtype, tgt_name agtype, r agtype)",
                config.getGraphName(),
                cypher
            );
            
            ResultSet rs = stmt.executeQuery(sql);
            
            while (rs.next()) {
                String srcName = cleanAgtypeString(rs.getString(1));
                String tgtName = cleanAgtypeString(rs.getString(2));
                String relationJson = rs.getString(3);
                
                Relation relation = parseRelationFromAgtype(srcName, tgtName, relationJson);
                if (relation != null) {
                    relations.add(relation);
                }
            }
            
        } catch (SQLException e) {
            logger.error("Failed to query relations: {}", cypher, e);
        }
        
        return relations;
    }
    
    /**
     * Queries Cypher for a count result.
     */
    private long queryCypherForCount(String cypher) {
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (count agtype)",
                config.getGraphName(),
                cypher
            );
            
            ResultSet rs = stmt.executeQuery(sql);
            
            if (rs.next()) {
                String countStr = rs.getString(1);
                return Long.parseLong(countStr);
            }
            
        } catch (SQLException e) {
            logger.error("Failed to query count: {}", cypher, e);
        }
        
        return 0;
    }
    
    /**
     * Parses an Entity from AGE's agtype JSON format.
     */
    private Entity parseEntityFromAgtype(String agtypeJson) {
        try {
            JsonNode node = objectMapper.readTree(agtypeJson);
            
            String name = getStringProperty(node, "name");
            String entityType = getStringProperty(node, "entity_type");
            String description = getStringProperty(node, "description");
            String sourceId = getStringProperty(node, "source_id");
            
            if (name == null) return null;
            
            return Entity.builder()
                .entityName(name)
                .entityType(entityType != null ? entityType : "UNKNOWN")
                .description(description != null ? description : "")
                .sourceId(sourceId != null ? sourceId : "")
                .build();
                
        } catch (Exception e) {
            logger.warn("Failed to parse entity from agtype: {}", agtypeJson, e);
            return null;
        }
    }
    
    /**
     * Parses a Relation from AGE's agtype JSON format.
     */
    private Relation parseRelationFromAgtype(String srcName, String tgtName, String agtypeJson) {
        try {
            JsonNode node = objectMapper.readTree(agtypeJson);
            
            String description = getStringProperty(node, "description");
            String keywords = getStringProperty(node, "keywords");
            String sourceId = getStringProperty(node, "source_id");
            double weight = getDoubleProperty(node, "weight", 1.0);
            
            return Relation.builder()
                .srcId(srcName)
                .tgtId(tgtName)
                .description(description != null ? description : "RELATED_TO")
                .keywords(keywords != null ? keywords : "")
                .sourceId(sourceId != null ? sourceId : "")
                .weight(weight)
                .build();
                
        } catch (Exception e) {
            logger.warn("Failed to parse relation from agtype: {}", agtypeJson, e);
            return null;
        }
    }
    
    /**
     * Extracts a string property from AGE JSON.
     */
    private String getStringProperty(JsonNode node, String propertyName) {
        if (node.has("properties") && node.get("properties").has(propertyName)) {
            return node.get("properties").get(propertyName).asText();
        }
        if (node.has(propertyName)) {
            return node.get(propertyName).asText();
        }
        return null;
    }
    
    /**
     * Extracts a double property from AGE JSON.
     */
    private double getDoubleProperty(JsonNode node, String propertyName, double defaultValue) {
        if (node.has("properties") && node.get("properties").has(propertyName)) {
            return node.get("properties").get(propertyName).asDouble(defaultValue);
        }
        if (node.has(propertyName)) {
            return node.get(propertyName).asDouble(defaultValue);
        }
        return defaultValue;
    }
    
    /**
     * Cleans agtype string values (removes quotes).
     */
    private String cleanAgtypeString(String value) {
        if (value == null) return "";
        return value.replace("\"", "").trim();
    }
    
    /**
     * Escapes special characters for Cypher queries.
     */
    private String escapeCypher(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                   .replace("'", "\\'")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }
}
