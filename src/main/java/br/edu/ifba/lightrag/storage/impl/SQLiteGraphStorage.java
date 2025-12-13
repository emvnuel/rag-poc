package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-based implementation of GraphStorage.
 * 
 * <p>Uses relational tables (graph_entities, graph_relations) to store the knowledge graph.
 * Traversal and shortest path operations use recursive CTEs for efficiency.</p>
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Entity and relation CRUD operations</li>
 *   <li>BFS traversal with depth and node limits</li>
 *   <li>Shortest path finding via recursive CTE</li>
 *   <li>Project isolation via project_id filtering</li>
 *   <li>Batch operations for performance</li>
 * </ul>
 */
public final class SQLiteGraphStorage implements GraphStorage {

    private static final Logger LOG = Logger.getLogger(SQLiteGraphStorage.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final SQLiteConnectionManager connectionManager;

    /**
     * Creates a new SQLiteGraphStorage.
     *
     * @param connectionManager the SQLite connection manager
     */
    public SQLiteGraphStorage(SQLiteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            LOG.info("Initialized SQLiteGraphStorage");
        });
    }

    // ========== Graph Lifecycle Methods ==========

    @Override
    public CompletableFuture<Void> createProjectGraph(@NotNull String projectId) {
        // With relational tables, there's no explicit graph creation needed
        // The project_id column provides isolation
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId) {
        return CompletableFuture.runAsync(() -> {
            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                
                // Delete relations first (due to referential integrity)
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM graph_relations WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    stmt.executeUpdate();
                }
                
                // Delete entities
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM graph_entities WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    stmt.executeUpdate();
                }
                
                conn.commit();
                LOG.debugf("Deleted graph for project %s", projectId);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to delete project graph: " + projectId, e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    LOG.warn("Failed to reset auto-commit", e);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> graphExists(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM graph_entities WHERE project_id = ? LIMIT 1";
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check graph existence", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
        });
    }

    // ========== Entity Operations ==========

    @Override
    public CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO graph_entities (id, project_id, name, entity_type, description, document_id, source_chunk_ids, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(project_id, name) DO UPDATE SET
                    entity_type = excluded.entity_type,
                    description = excluded.description,
                    document_id = excluded.document_id,
                    source_chunk_ids = excluded.source_chunk_ids,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, projectId);
                stmt.setString(3, entity.getEntityName().toLowerCase());
                stmt.setString(4, entity.getEntityType() != null ? entity.getEntityType() : "UNKNOWN");
                stmt.setString(5, entity.getDescription());
                stmt.setString(6, entity.getDocumentId());
                stmt.setString(7, toJson(entity.getSourceChunkIds()));
                
                stmt.executeUpdate();
                LOG.debugf("Upserted entity %s in project %s", entity.getEntityName(), projectId);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert entity: " + entity.getEntityName(), e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entities) {
        return CompletableFuture.runAsync(() -> {
            if (entities.isEmpty()) {
                return;
            }

            String sql = """
                INSERT INTO graph_entities (id, project_id, name, entity_type, description, document_id, source_chunk_ids, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(project_id, name) DO UPDATE SET
                    entity_type = excluded.entity_type,
                    description = excluded.description,
                    document_id = excluded.document_id,
                    source_chunk_ids = excluded.source_chunk_ids,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (Entity entity : entities) {
                        stmt.setString(1, UUID.randomUUID().toString());
                        stmt.setString(2, projectId);
                        stmt.setString(3, entity.getEntityName().toLowerCase());
                        stmt.setString(4, entity.getEntityType() != null ? entity.getEntityType() : "UNKNOWN");
                        stmt.setString(5, entity.getDescription());
                        stmt.setString(6, entity.getDocumentId());
                        stmt.setString(7, toJson(entity.getSourceChunkIds()));
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
                conn.commit();
                LOG.debugf("Batch upserted %d entities in project %s", entities.size(), projectId);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to batch upsert entities", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOG.warn("Failed to reset auto-commit", ex);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    // ========== Relation Operations ==========

    @Override
    public CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO graph_relations (id, project_id, source_entity, target_entity, relation_type, description, keywords, weight, document_id, source_chunk_ids, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(project_id, source_entity, target_entity) DO UPDATE SET
                    relation_type = excluded.relation_type,
                    description = excluded.description,
                    keywords = excluded.keywords,
                    weight = excluded.weight,
                    document_id = excluded.document_id,
                    source_chunk_ids = excluded.source_chunk_ids,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, projectId);
                stmt.setString(3, relation.getSrcId().toLowerCase());
                stmt.setString(4, relation.getTgtId().toLowerCase());
                stmt.setString(5, "RELATED_TO");
                stmt.setString(6, relation.getDescription());
                stmt.setString(7, relation.getKeywords());
                stmt.setDouble(8, relation.getWeight());
                stmt.setString(9, relation.getDocumentId());
                stmt.setString(10, toJson(relation.getSourceChunkIds()));
                
                stmt.executeUpdate();
                LOG.debugf("Upserted relation %s -> %s in project %s", 
                    relation.getSrcId(), relation.getTgtId(), projectId);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert relation", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations) {
        return CompletableFuture.runAsync(() -> {
            if (relations.isEmpty()) {
                return;
            }

            String sql = """
                INSERT INTO graph_relations (id, project_id, source_entity, target_entity, relation_type, description, keywords, weight, document_id, source_chunk_ids, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                ON CONFLICT(project_id, source_entity, target_entity) DO UPDATE SET
                    relation_type = excluded.relation_type,
                    description = excluded.description,
                    keywords = excluded.keywords,
                    weight = excluded.weight,
                    document_id = excluded.document_id,
                    source_chunk_ids = excluded.source_chunk_ids,
                    updated_at = datetime('now')
                """;

            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (Relation relation : relations) {
                        stmt.setString(1, UUID.randomUUID().toString());
                        stmt.setString(2, projectId);
                        stmt.setString(3, relation.getSrcId().toLowerCase());
                        stmt.setString(4, relation.getTgtId().toLowerCase());
                        stmt.setString(5, "RELATED_TO");
                        stmt.setString(6, relation.getDescription());
                        stmt.setString(7, relation.getKeywords());
                        stmt.setDouble(8, relation.getWeight());
                        stmt.setString(9, relation.getDocumentId());
                        stmt.setString(10, toJson(relation.getSourceChunkIds()));
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
                conn.commit();
                LOG.debugf("Batch upserted %d relations in project %s", relations.size(), projectId);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to batch upsert relations", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOG.warn("Failed to reset auto-commit", ex);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    // ========== Query Operations ==========

    @Override
    public CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT name, entity_type, description, document_id, source_chunk_ids
                FROM graph_entities
                WHERE project_id = ? AND name = ?
                """;

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, entityName.toLowerCase());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return entityFromResultSet(rs);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get entity: " + entityName, e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames) {
        return CompletableFuture.supplyAsync(() -> {
            if (entityNames.isEmpty()) {
                return Collections.emptyList();
            }

            StringBuilder sql = new StringBuilder("""
                SELECT name, entity_type, description, document_id, source_chunk_ids
                FROM graph_entities
                WHERE project_id = ? AND name IN (
                """);
            sql.append("?,".repeat(entityNames.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            List<Entity> entities = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setString(1, projectId);
                for (int i = 0; i < entityNames.size(); i++) {
                    stmt.setString(i + 2, entityNames.get(i).toLowerCase());
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        entities.add(entityFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get entities", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return entities;
        });
    }

    @Override
    public CompletableFuture<Relation> getRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT source_entity, target_entity, description, keywords, weight, document_id, source_chunk_ids
                FROM graph_relations
                WHERE project_id = ? AND source_entity = ? AND target_entity = ?
                """;

            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, srcId.toLowerCase());
                stmt.setString(3, tgtId.toLowerCase());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return relationFromResultSet(rs);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get relation", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT source_entity, target_entity, description, keywords, weight, document_id, source_chunk_ids
                FROM graph_relations
                WHERE project_id = ? AND (source_entity = ? OR target_entity = ?)
                """;

            List<Relation> relations = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, entityName.toLowerCase());
                stmt.setString(3, entityName.toLowerCase());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        relations.add(relationFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get relations for entity", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return relations;
        });
    }

    @Override
    public CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT name, entity_type, description, document_id, source_chunk_ids
                FROM graph_entities
                WHERE project_id = ?
                """;

            List<Entity> entities = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        entities.add(entityFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get all entities", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return entities;
        });
    }

    @Override
    public CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT source_entity, target_entity, description, keywords, weight, document_id, source_chunk_ids
                FROM graph_relations
                WHERE project_id = ?
                """;

            List<Relation> relations = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        relations.add(relationFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get all relations", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return relations;
        });
    }

    // ========== Batch Query Operations ==========

    @Override
    public CompletableFuture<List<Entity>> getEntitiesBySourceChunks(@NotNull String projectId, @NotNull List<String> chunkIds) {
        return CompletableFuture.supplyAsync(() -> {
            if (chunkIds.isEmpty()) {
                return Collections.emptyList();
            }

            // Query entities where source_chunk_ids JSON array contains any of the chunk IDs
            String sql = """
                SELECT name, entity_type, description, document_id, source_chunk_ids
                FROM graph_entities
                WHERE project_id = ?
                """;

            List<Entity> matchingEntities = new ArrayList<>();
            Set<String> chunkIdSet = new HashSet<>(chunkIds);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        List<String> sourceChunkIds = fromJson(rs.getString("source_chunk_ids"));
                        // Check if any chunk ID matches
                        for (String chunkId : sourceChunkIds) {
                            if (chunkIdSet.contains(chunkId)) {
                                matchingEntities.add(entityFromResultSet(rs));
                                break;
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get entities by source chunks", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return matchingEntities;
        });
    }

    @Override
    public CompletableFuture<List<Relation>> getRelationsBySourceChunks(@NotNull String projectId, @NotNull List<String> chunkIds) {
        return CompletableFuture.supplyAsync(() -> {
            if (chunkIds.isEmpty()) {
                return Collections.emptyList();
            }

            String sql = """
                SELECT source_entity, target_entity, description, keywords, weight, document_id, source_chunk_ids
                FROM graph_relations
                WHERE project_id = ?
                """;

            List<Relation> matchingRelations = new ArrayList<>();
            Set<String> chunkIdSet = new HashSet<>(chunkIds);
            
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        List<String> sourceChunkIds = fromJson(rs.getString("source_chunk_ids"));
                        for (String chunkId : sourceChunkIds) {
                            if (chunkIdSet.contains(chunkId)) {
                                matchingRelations.add(relationFromResultSet(rs));
                                break;
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get relations by source chunks", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return matchingRelations;
        });
    }

    @Override
    public CompletableFuture<List<Entity>> getEntitiesBatch(@NotNull String projectId, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT name, entity_type, description, document_id, source_chunk_ids
                FROM graph_entities
                WHERE project_id = ?
                ORDER BY name
                LIMIT ? OFFSET ?
                """;

            List<Entity> entities = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        entities.add(entityFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get entities batch", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return entities;
        });
    }

    @Override
    public CompletableFuture<List<Relation>> getRelationsBatch(@NotNull String projectId, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT source_entity, target_entity, description, keywords, weight, document_id, source_chunk_ids
                FROM graph_relations
                WHERE project_id = ?
                ORDER BY source_entity, target_entity
                LIMIT ? OFFSET ?
                """;

            List<Relation> relations = new ArrayList<>();
            Connection conn = connectionManager.getReadConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        relations.add(relationFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get relations batch", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            return relations;
        });
    }

    // ========== Delete Operations ==========

    @Override
    public CompletableFuture<Boolean> deleteEntity(@NotNull String projectId, @NotNull String entityName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM graph_entities WHERE project_id = ? AND name = ?";
            
            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, entityName.toLowerCase());
                int deleted = stmt.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete entity: " + entityName, e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteRelation(@NotNull String projectId, @NotNull String srcId, @NotNull String tgtId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM graph_relations WHERE project_id = ? AND source_entity = ? AND target_entity = ?";
            
            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, srcId.toLowerCase());
                stmt.setString(3, tgtId.toLowerCase());
                int deleted = stmt.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete relation", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteBySourceId(@NotNull String projectId, @NotNull String sourceId) {
        return CompletableFuture.supplyAsync(() -> {
            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                int total = 0;
                
                // Delete relations with matching document_id
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM graph_relations WHERE project_id = ? AND document_id = ?")) {
                    stmt.setString(1, projectId);
                    stmt.setString(2, sourceId);
                    total += stmt.executeUpdate();
                }
                
                // Delete entities with matching document_id
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM graph_entities WHERE project_id = ? AND document_id = ?")) {
                    stmt.setString(1, projectId);
                    stmt.setString(2, sourceId);
                    total += stmt.executeUpdate();
                }
                
                conn.commit();
                return total;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to delete by source ID: " + sourceId, e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOG.warn("Failed to reset auto-commit", ex);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteEntities(@NotNull String projectId, @NotNull Set<String> entityNames) {
        return CompletableFuture.supplyAsync(() -> {
            if (entityNames.isEmpty()) {
                return 0;
            }

            StringBuilder sql = new StringBuilder("DELETE FROM graph_entities WHERE project_id = ? AND name IN (");
            sql.append("?,".repeat(entityNames.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setString(1, projectId);
                int i = 2;
                for (String name : entityNames) {
                    stmt.setString(i++, name.toLowerCase());
                }
                return stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete entities", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteRelations(@NotNull String projectId, @NotNull Set<String> relationKeys) {
        return CompletableFuture.supplyAsync(() -> {
            if (relationKeys.isEmpty()) {
                return 0;
            }

            int totalDeleted = 0;
            Connection conn = connectionManager.getWriteConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM graph_relations WHERE project_id = ? AND source_entity = ? AND target_entity = ?")) {
                    for (String key : relationKeys) {
                        String[] parts = key.split("->");
                        if (parts.length == 2) {
                            stmt.setString(1, projectId);
                            stmt.setString(2, parts[0].trim().toLowerCase());
                            stmt.setString(3, parts[1].trim().toLowerCase());
                            totalDeleted += stmt.executeUpdate();
                        }
                    }
                }
                conn.commit();
                return totalDeleted;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.warn("Failed to rollback", rollbackEx);
                }
                throw new RuntimeException("Failed to delete relations", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOG.warn("Failed to reset auto-commit", ex);
                }
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    // ========== Update Operations ==========

    @Override
    public CompletableFuture<Void> updateEntityDescription(@NotNull String projectId, @NotNull String entityName, 
            @NotNull String description, @NotNull Set<String> sourceIds) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                UPDATE graph_entities
                SET description = ?, source_chunk_ids = ?, updated_at = datetime('now')
                WHERE project_id = ? AND name = ?
                """;

            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, description);
                stmt.setString(2, toJson(new ArrayList<>(sourceIds)));
                stmt.setString(3, projectId);
                stmt.setString(4, entityName.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update entity description", e);
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        });
    }

    // ========== Traversal Operations ==========

    @Override
    public CompletableFuture<GraphSubgraph> traverse(@NotNull String projectId, @NotNull String startEntity, int maxDepth) {
        return traverseBFS(projectId, startEntity, maxDepth, 0);
    }

    @Override
    public CompletableFuture<GraphSubgraph> traverseBFS(
            @NotNull String projectId, 
            @NotNull String startEntity, 
            int maxDepth, 
            int maxNodes) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> visitedEntities = new LinkedHashSet<>();
            Set<String> visitedRelationKeys = new HashSet<>();
            List<Relation> collectedRelations = new ArrayList<>();
            Queue<String> currentLevel = new LinkedList<>();
            
            currentLevel.add(startEntity.toLowerCase());
            visitedEntities.add(startEntity.toLowerCase());
            
            for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
                Queue<String> nextLevel = new LinkedList<>();
                
                while (!currentLevel.isEmpty()) {
                    // Check if we've reached the node limit
                    if (maxNodes > 0 && visitedEntities.size() >= maxNodes) {
                        // Clear remaining queue to stop processing
                        currentLevel.clear();
                        break;
                    }
                    
                    String entity = currentLevel.poll();
                    List<Relation> relations = getRelationsForEntity(projectId, entity).join();
                    
                    for (Relation relation : relations) {
                        // Create a unique key for the relation to avoid duplicates
                        String relationKey = relation.getSrcId().toLowerCase() + ":" + relation.getTgtId().toLowerCase();
                        if (!visitedRelationKeys.contains(relationKey)) {
                            visitedRelationKeys.add(relationKey);
                            collectedRelations.add(relation);
                        }
                        
                        // Add neighbors to next level only if we haven't reached the limit
                        if (maxNodes <= 0 || visitedEntities.size() < maxNodes) {
                            String neighbor = relation.getSrcId().equalsIgnoreCase(entity) 
                                ? relation.getTgtId() 
                                : relation.getSrcId();
                            
                            if (!visitedEntities.contains(neighbor.toLowerCase())) {
                                visitedEntities.add(neighbor.toLowerCase());
                                nextLevel.add(neighbor.toLowerCase());
                                
                                // Check limit again after adding
                                if (maxNodes > 0 && visitedEntities.size() >= maxNodes) {
                                    break;
                                }
                            }
                        }
                    }
                }
                
                currentLevel = nextLevel;
            }
            
            // Fetch all visited entities
            List<Entity> entities = getEntities(projectId, new ArrayList<>(visitedEntities)).join();
            
            return new GraphSubgraph(entities, collectedRelations);
        });
    }

    @Override
    public CompletableFuture<List<Entity>> findShortestPath(
            @NotNull String projectId, 
            @NotNull String sourceEntity, 
            @NotNull String targetEntity) {
        return CompletableFuture.supplyAsync(() -> {
            // BFS to find shortest path
            String source = sourceEntity.toLowerCase();
            String target = targetEntity.toLowerCase();
            
            if (source.equals(target)) {
                Entity entity = getEntity(projectId, source).join();
                return entity != null ? List.of(entity) : Collections.emptyList();
            }
            
            Map<String, String> parent = new HashMap<>();
            Set<String> visited = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            
            queue.add(source);
            visited.add(source);
            parent.put(source, null);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                
                if (current.equals(target)) {
                    // Reconstruct path
                    List<String> path = new ArrayList<>();
                    String node = target;
                    while (node != null) {
                        path.add(0, node);
                        node = parent.get(node);
                    }
                    return getEntities(projectId, path).join();
                }
                
                List<Relation> relations = getRelationsForEntity(projectId, current).join();
                for (Relation relation : relations) {
                    String neighbor = relation.getSrcId().equalsIgnoreCase(current) 
                        ? relation.getTgtId().toLowerCase() 
                        : relation.getSrcId().toLowerCase();
                    
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        parent.put(neighbor, current);
                        queue.add(neighbor);
                    }
                }
            }
            
            return Collections.emptyList();
        });
    }

    // ========== Batch Operations for Performance ==========

    @Override
    public CompletableFuture<Map<String, Integer>> getNodeDegreesBatch(
            @NotNull String projectId, 
            @NotNull List<String> entityNames, 
            int batchSize) {
        return CompletableFuture.supplyAsync(() -> {
            if (entityNames.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Integer> degrees = new HashMap<>();
            
            // Process in batches
            for (int i = 0; i < entityNames.size(); i += batchSize) {
                int end = Math.min(i + batchSize, entityNames.size());
                List<String> batch = entityNames.subList(i, end);
                
                StringBuilder sql = new StringBuilder("""
                    SELECT e.name, COUNT(r.id) as degree
                    FROM graph_entities e
                    LEFT JOIN graph_relations r ON 
                        (r.source_entity = e.name OR r.target_entity = e.name) 
                        AND r.project_id = e.project_id
                    WHERE e.project_id = ? AND e.name IN (
                    """);
                sql.append("?,".repeat(batch.size()));
                sql.setLength(sql.length() - 1);
                sql.append(") GROUP BY e.name");

                Connection conn = connectionManager.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    stmt.setString(1, projectId);
                    for (int j = 0; j < batch.size(); j++) {
                        stmt.setString(j + 2, batch.get(j).toLowerCase());
                    }
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            degrees.put(rs.getString("name"), rs.getInt("degree"));
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to get node degrees", e);
                } finally {
                    connectionManager.releaseReadConnection(conn);
                }
            }
            
            return degrees;
        });
    }

    @Override
    public CompletableFuture<Map<String, Entity>> getEntitiesMapBatch(
            @NotNull String projectId, 
            @NotNull List<String> entityNames, 
            int batchSize) {
        return CompletableFuture.supplyAsync(() -> {
            if (entityNames.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Entity> entityMap = new HashMap<>();
            
            for (int i = 0; i < entityNames.size(); i += batchSize) {
                int end = Math.min(i + batchSize, entityNames.size());
                List<String> batch = entityNames.subList(i, end);
                
                List<Entity> entities = getEntities(projectId, batch).join();
                for (Entity entity : entities) {
                    entityMap.put(entity.getEntityName(), entity);
                }
            }
            
            return entityMap;
        });
    }

    // ========== Statistics Operations ==========

    @Override
    public CompletableFuture<GraphStats> getStats(@NotNull String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String entityCountSql = "SELECT COUNT(*) FROM graph_entities WHERE project_id = ?";
            String relationCountSql = "SELECT COUNT(*) FROM graph_relations WHERE project_id = ?";
            
            long entityCount = 0;
            long relationCount = 0;
            
            Connection conn = connectionManager.getReadConnection();
            try {
                try (PreparedStatement stmt = conn.prepareStatement(entityCountSql)) {
                    stmt.setString(1, projectId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            entityCount = rs.getLong(1);
                        }
                    }
                }
                
                try (PreparedStatement stmt = conn.prepareStatement(relationCountSql)) {
                    stmt.setString(1, projectId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            relationCount = rs.getLong(1);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get graph stats", e);
            } finally {
                connectionManager.releaseReadConnection(conn);
            }
            
            double avgDegree = entityCount > 0 ? (double) (2 * relationCount) / entityCount : 0.0;
            return new GraphStats(entityCount, relationCount, avgDegree);
        });
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closed SQLiteGraphStorage");
    }

    // ========== Helper Methods ==========

    private Entity entityFromResultSet(ResultSet rs) throws SQLException {
        return new Entity(
            rs.getString("name"),
            rs.getString("entity_type"),
            rs.getString("description") != null ? rs.getString("description") : "",
            null, // filePath
            rs.getString("document_id"),
            fromJson(rs.getString("source_chunk_ids"))
        );
    }

    private Relation relationFromResultSet(ResultSet rs) throws SQLException {
        return new Relation(
            rs.getString("source_entity"),
            rs.getString("target_entity"),
            rs.getString("description") != null ? rs.getString("description") : "",
            rs.getString("keywords") != null ? rs.getString("keywords") : "",
            rs.getDouble("weight"),
            null, // filePath
            rs.getString("document_id"),
            fromJson(rs.getString("source_chunk_ids"))
        );
    }

    private String toJson(List<String> list) {
        try {
            return OBJECT_MAPPER.writeValueAsString(list != null ? list : Collections.emptyList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
