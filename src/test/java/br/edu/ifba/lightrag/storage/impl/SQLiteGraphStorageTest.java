package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphStats;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphSubgraph;

/**
 * Unit tests for SQLiteGraphStorage.
 * 
 * Tests verify:
 * 1. Entity CRUD operations
 * 2. Relation CRUD operations
 * 3. Graph traversal (BFS, shortest path)
 * 4. Project isolation
 * 5. Batch operations
 */
class SQLiteGraphStorageTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteGraphStorage graphStorage;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations to create schema
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        projectId = UUID.randomUUID().toString();
        createProject(projectId);
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (graphStorage != null) {
            graphStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    // ===== Entity Tests =====

    /**
     * Test basic entity upsert and retrieval.
     */
    @Test
    void testUpsertAndGetEntity() throws Exception {
        Entity entity = Entity.builder()
            .entityName("Apple Inc.")
            .entityType("ORGANIZATION")
            .description("Technology company founded by Steve Jobs")
            .sourceChunkIds(List.of("chunk1", "chunk2"))
            .build();
        
        graphStorage.upsertEntity(projectId, entity).join();
        
        Entity retrieved = graphStorage.getEntity(projectId, "Apple Inc.").join();
        assertNotNull(retrieved, "Entity should not be null");
        assertEquals("apple inc.", retrieved.getEntityName(), "Name should match (lowercase)");
        assertEquals("ORGANIZATION", retrieved.getEntityType(), "Type should match");
    }

    /**
     * Test batch entity upsert.
     */
    @Test
    void testUpsertEntities() throws Exception {
        List<Entity> entities = List.of(
            Entity.builder().entityName("Entity1").entityType("PERSON").description("Description 1").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Entity2").entityType("LOCATION").description("Description 2").addSourceChunkId("c2").build(),
            Entity.builder().entityName("Entity3").entityType("ORGANIZATION").description("Description 3").addSourceChunkId("c3").build()
        );
        
        graphStorage.upsertEntities(projectId, entities).join();
        
        List<Entity> retrieved = graphStorage.getAllEntities(projectId).join();
        assertEquals(3, retrieved.size(), "Should have 3 entities");
    }

    /**
     * Test entity update (upsert existing).
     */
    @Test
    void testEntityUpdate() throws Exception {
        Entity original = Entity.builder()
            .entityName("TestEntity")
            .entityType("PERSON")
            .description("Original description")
            .addSourceChunkId("c1")
            .build();
        graphStorage.upsertEntity(projectId, original).join();
        
        Entity updated = Entity.builder()
            .entityName("TestEntity")
            .entityType("PERSON")
            .description("Updated description")
            .sourceChunkIds(List.of("c1", "c2"))
            .build();
        graphStorage.upsertEntity(projectId, updated).join();
        
        Entity retrieved = graphStorage.getEntity(projectId, "TestEntity").join();
        assertEquals("Updated description", retrieved.getDescription(), "Description should be updated");
    }

    /**
     * Test entity deletion.
     */
    @Test
    void testDeleteEntity() throws Exception {
        Entity entity = Entity.builder()
            .entityName("ToDelete")
            .entityType("PERSON")
            .description("Will be deleted")
            .addSourceChunkId("c1")
            .build();
        graphStorage.upsertEntity(projectId, entity).join();
        
        Boolean deleted = graphStorage.deleteEntity(projectId, "ToDelete").join();
        assertTrue(deleted, "Should return true for deleted");
        
        Entity retrieved = graphStorage.getEntity(projectId, "ToDelete").join();
        assertNull(retrieved, "Entity should be null after deletion");
    }

    /**
     * Test batch entity deletion.
     */
    @Test
    void testDeleteEntities() throws Exception {
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("E1").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("E2").entityType("PERSON").description("Desc").addSourceChunkId("c2").build(),
            Entity.builder().entityName("E3").entityType("PERSON").description("Desc").addSourceChunkId("c3").build()
        )).join();
        
        Integer deleted = graphStorage.deleteEntities(projectId, Set.of("E1", "E2")).join();
        assertEquals(2, deleted, "Should delete 2 entities");
        
        List<Entity> remaining = graphStorage.getAllEntities(projectId).join();
        assertEquals(1, remaining.size(), "Should have 1 remaining entity");
    }

    // ===== Relation Tests =====

    /**
     * Test basic relation upsert and retrieval.
     */
    @Test
    void testUpsertAndGetRelation() throws Exception {
        // First create entities
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("Alice").entityType("PERSON").description("A person").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Bob").entityType("PERSON").description("Another person").addSourceChunkId("c1").build()
        )).join();
        
        Relation relation = Relation.builder()
            .srcId("Alice")
            .tgtId("Bob")
            .description("Alice knows Bob from work")
            .keywords("colleague,friend")
            .weight(1.0)
            .addSourceChunkId("c1")
            .build();
        
        graphStorage.upsertRelation(projectId, relation).join();
        
        Relation retrieved = graphStorage.getRelation(projectId, "Alice", "Bob").join();
        assertNotNull(retrieved, "Relation should not be null");
        assertEquals("alice", retrieved.getSrcId(), "Source should match (lowercase)");
        assertEquals("bob", retrieved.getTgtId(), "Target should match (lowercase)");
    }

    /**
     * Test get relations for entity.
     */
    @Test
    void testGetRelationsForEntity() throws Exception {
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("A").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("B").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("C").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("A").tgtId("B").description("KNOWS").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("A").tgtId("C").description("WORKS_WITH").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("B").tgtId("C").description("KNOWS").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
        )).join();
        
        List<Relation> relationsForA = graphStorage.getRelationsForEntity(projectId, "A").join();
        assertEquals(2, relationsForA.size(), "A should have 2 relations");
    }

    /**
     * Test relation deletion.
     */
    @Test
    void testDeleteRelation() throws Exception {
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("X").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Y").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelation(projectId, 
            Relation.builder().srcId("X").tgtId("Y").description("LINKED").keywords("kw").weight(1.0).addSourceChunkId("c1").build()).join();
        
        Boolean deleted = graphStorage.deleteRelation(projectId, "X", "Y").join();
        assertTrue(deleted, "Should return true for deleted");
        
        Relation retrieved = graphStorage.getRelation(projectId, "X", "Y").join();
        assertNull(retrieved, "Relation should be null after deletion");
    }

    // ===== Traversal Tests =====

    /**
     * Test graph traversal from starting entity.
     */
    @Test
    void testTraverse() throws Exception {
        // Create a simple graph: A -> B -> C
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("A").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("B").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("C").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("A").tgtId("B").description("LINKS_TO").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("B").tgtId("C").description("LINKS_TO").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
        )).join();
        
        GraphSubgraph subgraph = graphStorage.traverse(projectId, "A", 2).join();
        assertNotNull(subgraph, "Subgraph should not be null");
        assertEquals(3, subgraph.entities().size(), "Should have 3 entities");
        assertEquals(2, subgraph.relations().size(), "Should have 2 relations");
    }

    /**
     * Test BFS traversal with node limit.
     */
    @Test
    void testTraverseBFS() throws Exception {
        // Create a wider graph
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("Root").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Child1").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Child2").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Child3").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("Grandchild1").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("Root").tgtId("Child1").description("PARENT_OF").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("Root").tgtId("Child2").description("PARENT_OF").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("Root").tgtId("Child3").description("PARENT_OF").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("Child1").tgtId("Grandchild1").description("PARENT_OF").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
        )).join();
        
        // Limit to 3 nodes
        GraphSubgraph subgraph = graphStorage.traverseBFS(projectId, "Root", 10, 3).join();
        assertTrue(subgraph.entities().size() <= 3, "Should have at most 3 entities");
    }

    /**
     * Test shortest path finding.
     */
    @Test
    void testFindShortestPath() throws Exception {
        // Create graph: A -> B -> C -> D
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("A").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("B").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("C").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("D").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("A").tgtId("B").description("LINKS").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("B").tgtId("C").description("LINKS").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("C").tgtId("D").description("LINKS").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
        )).join();
        
        List<Entity> path = graphStorage.findShortestPath(projectId, "A", "D").join();
        assertNotNull(path, "Path should not be null");
        assertEquals(4, path.size(), "Path should have 4 entities");
    }

    // ===== Project Isolation Tests =====

    /**
     * Test entities are isolated by project.
     */
    @Test
    void testProjectIsolation() throws Exception {
        String project1 = UUID.randomUUID().toString();
        String project2 = UUID.randomUUID().toString();
        
        createProject(project1);
        createProject(project2);
        graphStorage.createProjectGraph(project1).join();
        graphStorage.createProjectGraph(project2).join();
        
        graphStorage.upsertEntity(project1, 
            Entity.builder().entityName("SharedName").entityType("PERSON").description("In project 1").addSourceChunkId("c1").build()).join();
        graphStorage.upsertEntity(project2, 
            Entity.builder().entityName("SharedName").entityType("PERSON").description("In project 2").addSourceChunkId("c1").build()).join();
        
        Entity fromP1 = graphStorage.getEntity(project1, "SharedName").join();
        Entity fromP2 = graphStorage.getEntity(project2, "SharedName").join();
        
        assertEquals("In project 1", fromP1.getDescription(), "Should get project 1 entity");
        assertEquals("In project 2", fromP2.getDescription(), "Should get project 2 entity");
    }

    // ===== Statistics Tests =====

    /**
     * Test graph statistics.
     */
    @Test
    void testGetStats() throws Exception {
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("E1").entityType("PERSON").description("Desc").addSourceChunkId("c1").build(),
            Entity.builder().entityName("E2").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelation(projectId,
            Relation.builder().srcId("E1").tgtId("E2").description("KNOWS").keywords("kw").weight(1.0).addSourceChunkId("c1").build()).join();
        
        GraphStats stats = graphStorage.getStats(projectId).join();
        assertEquals(2, stats.entityCount(), "Should have 2 entities");
        assertEquals(1, stats.relationCount(), "Should have 1 relation");
    }

    /**
     * Test graph deletion.
     */
    @Test
    void testDeleteProjectGraph() throws Exception {
        String tempProject = UUID.randomUUID().toString();
        createProject(tempProject);
        graphStorage.createProjectGraph(tempProject).join();
        
        graphStorage.upsertEntity(tempProject,
            Entity.builder().entityName("TempEntity").entityType("PERSON").description("Desc").addSourceChunkId("c1").build()).join();
        
        graphStorage.deleteProjectGraph(tempProject).join();
        
        Boolean exists = graphStorage.graphExists(tempProject).join();
        assertFalse(exists, "Graph should not exist after deletion");
    }

    // ===== Helper Methods =====

    /**
     * Creates a project record to satisfy foreign key constraints.
     */
    private void createProject(String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, "Test Project");
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }
}
