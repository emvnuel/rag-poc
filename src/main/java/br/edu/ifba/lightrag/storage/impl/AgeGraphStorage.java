package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.utils.TransientSQLExceptionPredicate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.smallrye.faulttolerance.api.RetryWhen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.temporal.ChronoUnit;
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
                logger.info("Initializing Apache AGE graph storage with per-project isolation");
                // Note: Individual project graphs are created on-demand via createProjectGraph()
                // We no longer create a shared graph during initialization
                logger.info("AGE graph storage initialized successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize AGE graph storage", e);
                throw new RuntimeException("Failed to initialize AGE graph storage", e);
            }
        }, executor);
    }
    
    // ===== Graph Lifecycle Methods =====
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> createProjectGraph(@NotNull String projectId) {
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                // Load AGE extension
                stmt.execute("LOAD 'age'");
                stmt.execute("SET search_path = ag_catalog, \"$user\", public");
                
                // Check if graph already exists (idempotency)
                if (graphExistsSync(conn, graphName)) {
                    logger.debug("Graph already exists for project: {}, graph name: {}", projectId, graphName);
                    return;
                }
                
                // Create graph
                String createGraphSql = String.format(
                    "SELECT * FROM ag_catalog.create_graph('%s')", graphName
                );
                stmt.execute(createGraphSql);
                
                logger.info("Creating graph for project: {}, graph name: {}", projectId, graphName);
                
                // Pre-create labels to avoid race conditions
                createLabelIfNotExists(conn, graphName, "Entity");
                createLabelIfNotExists(conn, graphName, "RELATED_TO");
                
                logger.info("Graph created successfully for project: {}", projectId);
                
            } catch (SQLException e) {
                logger.error("Failed to create graph for project: {}", projectId, e);
                throw new RuntimeException("Failed to create graph for project: " + projectId, e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId) {
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                stmt.execute("LOAD 'age'");
                stmt.execute("SET search_path = ag_catalog, \"$user\", public");
                
                // Check if graph exists (idempotency)
                if (!graphExistsSync(conn, graphName)) {
                    logger.warn("Graph doesn't exist for project: {}, graph name: {}", projectId, graphName);
                    return;
                }
                
                logger.info("Deleting graph for project: {}, graph name: {}", projectId, graphName);
                
                // Drop graph with cascade to remove all entities and relations
                String dropGraphSql = String.format(
                    "SELECT * FROM ag_catalog.drop_graph('%s', true)", graphName
                );
                stmt.execute(dropGraphSql);
                
                logger.info("Graph deleted successfully for project: {}", projectId);
                
            } catch (SQLException e) {
                logger.error("Failed to delete graph for project: {}", projectId, e);
                throw new RuntimeException("Failed to delete graph for project: " + projectId, e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Boolean> graphExists(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection()) {
                return graphExistsSync(conn, graphName);
            } catch (SQLException e) {
                logger.error("Failed to check graph existence for project: {}", projectId, e);
                return false;
            }
        }, executor);
    }
    
    // ===== Helper Methods for Graph Lifecycle =====
    
    /**
     * Generates a graph name from a project UUID.
     * 
     * Format: graph_<uuid_prefix>
     * - Remove hyphens from UUID
     * - Truncate to 32 characters (AGE limit: 63 chars, "graph_" prefix = 6 chars)
     * - Add "graph_" prefix
     * 
     * Example: 01938cc9-7c5e-7890-abcd-1234567890ab -> graph_01938cc97c5e7890abcd123456789
     * 
     * @param projectId the project UUID
     * @return the graph name
     */
    private String getGraphName(@NotNull String projectId) {
        String sanitized = projectId.replace("-", "");
        String truncated = sanitized.substring(0, Math.min(32, sanitized.length()));
        return "graph_" + truncated;
    }
    
    /**
     * Validates that projectId is not null and is a valid UUID format.
     * 
     * @param projectId the project UUID to validate
     * @throws IllegalArgumentException if projectId is null or invalid
     */
    private void validateProjectId(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId must not be null or empty");
        }
        
        // Basic UUID format validation (with or without hyphens)
        String uuidPattern = "^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$";
        if (!projectId.matches(uuidPattern)) {
            throw new IllegalArgumentException("projectId must be a valid UUID v7 format: " + projectId);
        }
    }
    
    /**
     * Synchronously checks if a graph exists.
     * 
     * @param conn the database connection
     * @param graphName the graph name to check
     * @return true if graph exists, false otherwise
     */
    private boolean graphExistsSync(Connection conn, String graphName) throws SQLException {
        String checkSql = String.format(
            "SELECT EXISTS(SELECT 1 FROM ag_catalog.ag_graph WHERE name = '%s')",
            graphName
        );
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (rs.next()) {
                return rs.getBoolean(1);
            }
        }
        
        return false;
    }
    
    /**
     * Creates a label (node or edge type) if it doesn't already exist.
     * This prevents "relation already exists" errors during concurrent operations.
     * 
     * @param conn the database connection
     * @param graphName the graph name
     * @param labelName the label name (e.g., "Entity", "RELATED_TO")
     */
    private void createLabelIfNotExists(Connection conn, String graphName, String labelName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Check if label exists in ag_catalog.ag_label
            String checkSql = String.format(
                "SELECT EXISTS(SELECT 1 FROM ag_catalog.ag_label WHERE name = '%s' AND graph = " +
                "(SELECT graphid FROM ag_catalog.ag_graph WHERE name = '%s'))",
                labelName, graphName
            );
            
            ResultSet rs = stmt.executeQuery(checkSql);
            boolean exists = false;
            if (rs.next()) {
                exists = rs.getBoolean(1);
            }
            rs.close();
            
            // If label doesn't exist, create it with a dummy node/edge
            if (!exists) {
                String cypher;
                if ("RELATED_TO".equals(labelName)) {
                    // Create a dummy edge and then delete it to create the label
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "CREATE (a:_TempNode {temp: true})-[r:RELATED_TO {temp: true}]->(b:_TempNode {temp: true}) " +
                        "RETURN r $$) AS (result agtype)",
                        graphName
                    );
                    stmt.execute(cypher);
                    
                    // Clean up temp nodes
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "MATCH (n:_TempNode {temp: true}) DETACH DELETE n $$) AS (result agtype)",
                        graphName
                    );
                    stmt.execute(cypher);
                } else {
                    // Create a dummy node and then delete it to create the label
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "CREATE (n:%s {temp: true}) RETURN n $$) AS (result agtype)",
                        graphName, labelName
                    );
                    stmt.execute(cypher);
                    
                    // Clean up temp node
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "MATCH (n:%s {temp: true}) DELETE n $$) AS (result agtype)",
                        graphName, labelName
                    );
                    stmt.execute(cypher);
                }
            }
        }
    }
    
    /**
     * Validates that a graph exists for the project before performing operations.
     * 
     * @param projectId the project UUID
     * @throws IllegalStateException if graph doesn't exist for the project
     */
    private void validateGraphExists(@NotNull String projectId) {
        String graphName = getGraphName(projectId);
        try (Connection conn = config.getConnection()) {
            if (!graphExistsSync(conn, graphName)) {
                throw new IllegalStateException(
                    String.format("Graph not found for project: %s (graph name: %s)", projectId, graphName)
                );
            }
        } catch (SQLException e) {
            logger.error("Failed to validate graph existence for project: {}", projectId, e);
            throw new RuntimeException("Failed to validate graph existence", e);
        }
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity) {
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                // FIX: MERGE only on 'name' to avoid duplicates within the same project
                // Since AGE doesn't support ON CREATE SET, we use MERGE on name only
                // Properties may get updated but entity deduplication is preserved
                // Case normalization applied to prevent "TechCorp" vs "Techcorp" duplicates
                
                String normalizedName = normalizeEntityName(entity.getEntityName());
                String mergeCypher = String.format(
                    "MERGE (e:Entity {name: '%s'}) " +
                    "SET e.entity_type = '%s', e.description = '%s', e.document_id = %s " +
                    "RETURN e",
                    escapeCypher(normalizedName),
                    escapeCypher(entity.getEntityType()),
                    escapeCypher(entity.getDescription()),
                    entity.getDocumentId() != null ? "'" + escapeCypher(entity.getDocumentId()) + "'" : "null"
                );
                logger.debug("Executing MERGE for entity '{}' (original: '{}') on graph {} for project {}: {}", 
                    normalizedName, entity.getEntityName(), graphName, projectId, mergeCypher);
                executeCypherWithConnection(conn, graphName, mergeCypher);
                
                conn.commit();
            } catch (SQLException e) {
                logger.error("Failed to upsert entity for project: {}", projectId, e);
                throw new RuntimeException("Failed to upsert entity", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entities) {
        if (entities.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                // FIX: MERGE only on 'name' to avoid duplicates within the same project
                // Since AGE doesn't support ON CREATE SET, we use MERGE on name only
                // Properties may get updated but entity deduplication is preserved
                // Case normalization applied to prevent "TechCorp" vs "Techcorp" duplicates
                
                for (Entity entity : entities) {
                    String normalizedName = normalizeEntityName(entity.getEntityName());
                    String mergeCypher = String.format(
                        "MERGE (e:Entity {name: '%s'}) " +
                        "SET e.entity_type = '%s', e.description = '%s', e.document_id = %s " +
                        "RETURN e",
                        escapeCypher(normalizedName),
                        escapeCypher(entity.getEntityType()),
                        escapeCypher(entity.getDescription()),
                        entity.getDocumentId() != null ? "'" + escapeCypher(entity.getDocumentId()) + "'" : "null"
                    );
                    logger.debug("Executing batch MERGE for entity '{}' (original: '{}') on graph {} for project {}: {}", 
                        normalizedName, entity.getEntityName(), graphName, projectId, mergeCypher);
                    executeCypherWithConnection(conn, graphName, mergeCypher);
                }
                
                conn.commit();
                logger.debug("Upserted {} entities for project: {}", entities.size(), projectId);
                
            } catch (SQLException e) {
                logger.error("Failed to upsert entities for project: {}", projectId, e);
                throw new RuntimeException("Failed to upsert entities", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation) {
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // FIX: MERGE only on entity names and relation direction, not on properties
                    // Properties may get updated but relation deduplication is preserved
                    // Case normalization applied to prevent "TechCorp" vs "Techcorp" duplicates
                    
                    String normalizedSrc = normalizeEntityName(relation.getSrcId());
                    String normalizedTgt = normalizeEntityName(relation.getTgtId());
                    String mergeCypher = String.format(Locale.US,
                        "MERGE (src:Entity {name: '%s'}) " +
                        "MERGE (tgt:Entity {name: '%s'}) " +
                        "MERGE (src)-[r:RELATED_TO]->(tgt) " +
                        "SET r.description = '%s', r.keywords = '%s', r.weight = %f, r.document_id = %s " +
                        "RETURN r",
                        escapeCypher(normalizedSrc),
                        escapeCypher(normalizedTgt),
                        escapeCypher(relation.getDescription()),
                        escapeCypher(relation.getKeywords()),
                        relation.getWeight(),
                        relation.getDocumentId() != null ? "'" + escapeCypher(relation.getDocumentId()) + "'" : "null"
                    );
                    logger.debug("Executing MERGE for relation {} -> {} on graph {} for project {}", 
                        normalizedSrc, normalizedTgt, graphName, projectId);
                    executeCypherWithConnection(conn, graphName, mergeCypher);
                    
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    logger.error("Failed to upsert relation: src={}, tgt={}, project={}", 
                        relation.getSrcId(), relation.getTgtId(), projectId, e);
                    throw e;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert relation", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations) {
        if (relations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // FIX: MERGE only on entity names and relation direction, not on properties
                    // Properties may get updated but relation deduplication is preserved
                    // Case normalization applied to prevent "TechCorp" vs "Techcorp" duplicates
                    
                    for (Relation relation : relations) {
                    String normalizedSrc = normalizeEntityName(relation.getSrcId());
                    String normalizedTgt = normalizeEntityName(relation.getTgtId());
                    String mergeCypher = String.format(Locale.US,
                        "MERGE (src:Entity {name: '%s'}) " +
                        "MERGE (tgt:Entity {name: '%s'}) " +
                        "MERGE (src)-[r:RELATED_TO]->(tgt) " +
                        "SET r.description = '%s', r.keywords = '%s', r.weight = %f, r.document_id = %s " +
                        "RETURN r",
                        escapeCypher(normalizedSrc),
                        escapeCypher(normalizedTgt),
                        escapeCypher(relation.getDescription()),
                        escapeCypher(relation.getKeywords()),
                        relation.getWeight(),
                        relation.getDocumentId() != null ? "'" + escapeCypher(relation.getDocumentId()) + "'" : "null"
                    );
                    logger.debug("Executing MERGE for relation {} -> {} on graph {} for project {}", 
                        normalizedSrc, normalizedTgt, graphName, projectId);
                    executeCypherWithConnection(conn, graphName, mergeCypher);
                    }
                    
                    conn.commit();
                    logger.debug("Upserted {} relations for project: {}", relations.size(), projectId);
                } catch (SQLException e) {
                    conn.rollback();
                    logger.error("Failed to upsert relations for project: {}", projectId, e);
                    throw e;
                }
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert relations", e);
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedName = normalizeEntityName(entityName);
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'}) RETURN e",
                escapeCypher(normalizedName)
            );
            
            List<Entity> results = queryCypherForEntities(graphName, cypher);
            logger.debug("Retrieved entity {} on graph {} for project {}", entityName, graphName, projectId);
            return results.isEmpty() ? null : results.get(0);
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames) {
        if (entityNames.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String namesClause = entityNames.stream()
                .map(name -> "'" + escapeCypher(normalizeEntityName(name)) + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            
            String cypher = String.format(
                "MATCH (e:Entity) WHERE e.name IN [%s] RETURN e",
                namesClause
            );
            
            List<Entity> results = queryCypherForEntities(graphName, cypher);
            logger.debug("Retrieved {} entities on graph {} for project {}", results.size(), graphName, projectId);
            return results;
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<Relation> getRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedSrc = normalizeEntityName(srcId);
            String normalizedTgt = normalizeEntityName(tgtId);
            String cypher = String.format(
                "MATCH (src:Entity {name: '%s'})-[r:RELATED_TO]->(tgt:Entity {name: '%s'}) " +
                "RETURN src.name, tgt.name, r",
                escapeCypher(normalizedSrc),
                escapeCypher(normalizedTgt)
            );
            
            List<Relation> results = queryCypherForRelations(graphName, cypher);
            logger.debug("Retrieved relation {} -> {} on graph {} for project {}", srcId, tgtId, graphName, projectId);
            return results.isEmpty() ? null : results.get(0);
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedName = normalizeEntityName(entityName);
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'})-[r:RELATED_TO]-(other:Entity) " +
                "RETURN e.name, other.name, r",
                escapeCypher(normalizedName)
            );
            
            List<Relation> results = queryCypherForRelations(graphName, cypher);
            logger.debug("Retrieved {} relations for entity {} on graph {} for project {}", results.size(), entityName, graphName, projectId);
            return results;
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String cypher = "MATCH (e:Entity) RETURN e";
            List<Entity> results = queryCypherForEntities(graphName, cypher);
            logger.debug("Retrieved all {} entities on graph {} for project {}", results.size(), graphName, projectId);
            return results;
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String cypher = "MATCH (src:Entity)-[r:RELATED_TO]->(tgt:Entity) RETURN src.name, tgt.name, r";
            List<Relation> results = queryCypherForRelations(graphName, cypher);
            logger.debug("Retrieved all {} relations on graph {} for project {}", results.size(), graphName, projectId);
            return results;
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> deleteEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedName = normalizeEntityName(entityName);
            String cypher = String.format(
                "MATCH (e:Entity {name: '%s'}) DETACH DELETE e",
                escapeCypher(normalizedName)
            );
            
            try {
                executeCypher(graphName, cypher);
                logger.debug("Deleted entity {} on graph {} for project {}", entityName, graphName, projectId);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to delete entity {} on graph {} for project {}: {}", entityName, graphName, projectId, e.getMessage());
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> deleteRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedSrc = normalizeEntityName(srcId);
            String normalizedTgt = normalizeEntityName(tgtId);
            String cypher = String.format(
                "MATCH (src:Entity {name: '%s'})-[r:RELATED_TO]->(tgt:Entity {name: '%s'}) DELETE r",
                escapeCypher(normalizedSrc),
                escapeCypher(normalizedTgt)
            );
            
            try {
                executeCypher(graphName, cypher);
                logger.debug("Deleted relation {} -> {} on graph {} for project {}", srcId, tgtId, graphName, projectId);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to delete relation {} -> {} on graph {} for project {}: {}", srcId, tgtId, graphName, projectId, e.getMessage());
                return false;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Integer> deleteBySourceId(@NotNull String projectId, @NotNull String sourceId) {
        // Renamed to deleteByDocumentId for clarity, but keeping this for backward compatibility
        return deleteByDocumentId(projectId, sourceId);
    }
    
    /**
     * Deletes all entities and relations associated with a specific document.
     * This ensures proper cleanup when documents are deleted.
     * 
     * @param projectId the project UUID
     * @param documentId the document UUID to delete entities/relations for
     * @return CompletableFuture with the number of entities deleted
     */
    public CompletableFuture<Integer> deleteByDocumentId(@NotNull String projectId, @NotNull String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try (Connection conn = config.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                stmt.execute("LOAD 'age'");
                stmt.execute("SET search_path = ag_catalog, \"$user\", public");
                
                int entitiesDeleted = 0;
                int relationsDeleted = 0;
                
                // Step 1: Delete all relations with this document_id
                String deleteRelationsCypher = String.format(
                    "MATCH ()-[r:RELATED_TO]->() WHERE r.document_id = '%s' DELETE r",
                    escapeCypher(documentId)
                );
                
                String deleteRelationsSql = String.format(
                    "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result agtype)",
                    graphName,
                    deleteRelationsCypher
                );
                
                try (ResultSet rs = stmt.executeQuery(deleteRelationsSql)) {
                    while (rs.next()) {
                        relationsDeleted++;
                    }
                }
                
                // Step 2: Delete all entities with this document_id
                // Use DETACH DELETE to remove any remaining relationships
                String deleteEntitiesCypher = String.format(
                    "MATCH (e:Entity) WHERE e.document_id = '%s' DETACH DELETE e",
                    escapeCypher(documentId)
                );
                
                String deleteEntitiesSql = String.format(
                    "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result agtype)",
                    graphName,
                    deleteEntitiesCypher
                );
                
                try (ResultSet rs = stmt.executeQuery(deleteEntitiesSql)) {
                    while (rs.next()) {
                        entitiesDeleted++;
                    }
                }
                
                logger.info("Deleted graph data for document {} in project {}: {} entities, {} relations",
                    documentId, projectId, entitiesDeleted, relationsDeleted);
                
                return entitiesDeleted + relationsDeleted;
                
            } catch (SQLException e) {
                logger.error("Failed to delete graph data for document {} in project {}", documentId, projectId, e);
                throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<GraphSubgraph> traverse(@NotNull String projectId, @NotNull String startEntity, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try {
                List<Entity> entities = new ArrayList<>();
                List<Relation> relations = new ArrayList<>();
                
                String normalizedStart = normalizeEntityName(startEntity);
                
                // Get all entities within maxDepth hops from start entity
                String entitiesCypher = String.format(
                    "MATCH (start:Entity {name: '%s'})-[*0..%d]-(e:Entity) RETURN DISTINCT e",
                    escapeCypher(normalizedStart),
                    maxDepth
                );
                entities = queryCypherForEntities(graphName, entitiesCypher);
                
                // Get all relations between entities within maxDepth
                String relationsCypher = String.format(
                    "MATCH (start:Entity {name: '%s'})-[*0..%d]-(e:Entity)-[r:RELATED_TO]-(other:Entity) " +
                    "RETURN DISTINCT e.name, other.name, r",
                    escapeCypher(normalizedStart),
                    maxDepth
                );
                relations = queryCypherForRelations(graphName, relationsCypher);
                
                logger.debug("Traversed from entity {} (maxDepth={}) on graph {} for project {}: {} entities, {} relations", 
                    startEntity, maxDepth, graphName, projectId, entities.size(), relations.size());
                return new GraphSubgraph(entities, relations);
                
            } catch (Exception e) {
                logger.error("Failed to traverse from entity {} on graph {} for project {}: {}", startEntity, graphName, projectId, e.getMessage());
                return new GraphSubgraph(List.of(), List.of());
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<List<Entity>> findShortestPath(@NotNull String projectId, @NotNull String sourceEntity, @NotNull String targetEntity) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            String normalizedSrc = normalizeEntityName(sourceEntity);
            String normalizedTgt = normalizeEntityName(targetEntity);
            String cypher = String.format(
                "MATCH path = shortestPath((src:Entity {name: '%s'})-[*]-(tgt:Entity {name: '%s'})) " +
                "RETURN nodes(path)",
                escapeCypher(normalizedSrc),
                escapeCypher(normalizedTgt)
            );
            
            try {
                List<Entity> path = queryCypherForEntities(graphName, cypher);
                logger.debug("Found shortest path {} -> {} on graph {} for project {}: {} nodes", 
                    sourceEntity, targetEntity, graphName, projectId, path.size());
                return path;
            } catch (Exception e) {
                logger.warn("Failed to find shortest path {} -> {} on graph {} for project {}: {}", 
                    sourceEntity, targetEntity, graphName, projectId, e.getMessage());
                return List.of();
            }
        }, executor);
    }
    
    @Override
    @Retry(maxRetries = 3, delay = 200, delayUnit = ChronoUnit.MILLIS, maxDuration = 30, durationUnit = ChronoUnit.SECONDS)
    @ExponentialBackoff(maxDelay = 5, maxDelayUnit = ChronoUnit.SECONDS)
    @RetryWhen(exception = TransientSQLExceptionPredicate.class)
    public CompletableFuture<GraphStats> getStats(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            validateProjectId(projectId);
            validateGraphExists(projectId);
            String graphName = getGraphName(projectId);
            
            try {
                // Count entities
                String entityCountCypher = "MATCH (e:Entity) RETURN count(e) AS count";
                long entityCount = queryCypherForCount(graphName, entityCountCypher);
                
                // Count relations
                String relationCountCypher = "MATCH ()-[r:RELATED_TO]->() RETURN count(r) AS count";
                long relationCount = queryCypherForCount(graphName, relationCountCypher);
                
                // Calculate average degree
                double avgDegree = entityCount > 0 ? (2.0 * relationCount / entityCount) : 0.0;
                
                logger.debug("Retrieved stats for graph {} (project {}): {} entities, {} relations, avg degree {}", 
                    graphName, projectId, entityCount, relationCount, avgDegree);
                return new GraphStats(entityCount, relationCount, avgDegree);
                
            } catch (Exception e) {
                logger.error("Failed to get graph stats for graph {} (project {}): {}", graphName, projectId, e.getMessage());
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
     * 
     * @param graphName the graph name to execute the query on
     * @param cypher the Cypher query to execute
     */
    private void executeCypher(String graphName, String cypher) {
        try (Connection conn = config.getConnection()) {
            logger.debug("Executing Cypher query on graph {}: {}", graphName, cypher);
            executeCypherWithConnection(conn, graphName, cypher);
        } catch (SQLException e) {
            logger.error("Failed to execute Cypher query: {}", cypher, e);
            throw new RuntimeException("Failed to execute Cypher query", e);
        }
    }
    
    /**
     * Executes a Cypher query with an existing connection.
     * Note: Must consume ResultSet to ensure query execution completes in AGE.
     * Automatically determines the result signature based on query type.
     * 
     * @param conn the database connection
     * @param graphName the graph name to execute the query on
     * @param cypher the Cypher query to execute
     */
    private void executeCypherWithConnection(Connection conn, String graphName, String cypher) throws SQLException {
        // Determine the result signature based on the query
        String resultSignature;
        if (cypher.contains("RETURN src, tgt, r")) {
            resultSignature = "src agtype, tgt agtype, r agtype";
        } else if (cypher.contains("RETURN src, tgt")) {
            resultSignature = "src agtype, tgt agtype";
        } else if (cypher.contains("RETURN e")) {
            resultSignature = "e agtype";
        } else if (cypher.contains("RETURN r")) {
            resultSignature = "r agtype";
        } else if (cypher.contains("RETURN")) {
            resultSignature = "result agtype";
        } else {
            // For DELETE and other non-returning queries
            resultSignature = "result agtype";
        }
        
        String sql = String.format(
            "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (%s)",
            graphName,
            cypher,
            resultSignature
        );
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            // Execute and consume result set (required for proper execution in AGE)
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    // Consume result set - this is required for AGE to commit changes
                }
            } catch (SQLException e) {
                // Log the failing query for debugging
                logger.error("Failed to execute Cypher query. Query: {}", 
                    cypher.length() > 200 ? cypher.substring(0, 200) + "..." : cypher, e);
                throw e;
            }
        }
    }
    
    /**
     * Queries Cypher and parses Entity results.
     * 
     * @param graphName the graph name to execute the query on
     * @param cypher the Cypher query to execute
     * @return list of entities
     */
    private List<Entity> queryCypherForEntities(String graphName, String cypher) {
        List<Entity> entities = new ArrayList<>();
        
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (e agtype)",
                graphName,
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
     * 
     * @param graphName the graph name to execute the query on
     * @param cypher the Cypher query to execute
     * @return list of relations
     */
    private List<Relation> queryCypherForRelations(String graphName, String cypher) {
        List<Relation> relations = new ArrayList<>();
        
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (src_name agtype, tgt_name agtype, r agtype)",
                graphName,
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
     * 
     * @param graphName the graph name to execute the query on
     * @param cypher the Cypher query to execute
     * @return the count result
     */
    private long queryCypherForCount(String graphName, String cypher) {
        try (Connection conn = config.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String sql = String.format(
                "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (count agtype)",
                graphName,
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
            
            if (name == null) return null;
            
            return Entity.builder()
                .entityName(name)
                .entityType(entityType != null ? entityType : "UNKNOWN")
                .description(description != null ? description : "")
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
            double weight = getDoubleProperty(node, "weight", 1.0);
            
            return Relation.builder()
                .srcId(srcName)
                .tgtId(tgtName)
                .description(description != null ? description : "RELATED_TO")
                .keywords(keywords != null ? keywords : "")
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
     * Normalizes entity name to lowercase for case-insensitive matching.
     * This prevents duplicates like "TechCorp" and "Techcorp".
     */
    private String normalizeEntityName(String name) {
        if (name == null) return "";
        final String normalized = name.toLowerCase().trim();
        logger.debug("Normalizing entity name: '{}' -> '{}'", name, normalized);
        return normalized;
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
