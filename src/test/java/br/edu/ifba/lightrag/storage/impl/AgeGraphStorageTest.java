package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test suite for AgeGraphStorage focusing on project-level graph isolation.
 * 
 * Tests verify that:
 * 1. Each project has an isolated graph
 * 2. Entities and relations don't leak across projects
 * 3. Entity deduplication works only within the same project
 * 4. Cross-project queries return empty results
 * 5. Graph deletion properly cleans up project data
 */
@QuarkusTest
class AgeGraphStorageTest {

    @Inject
    GraphStorage graphStorage;

    private String projectId1;
    private String projectId2;

    @BeforeEach
    void setUp() throws Exception {
        // Generate two project UUIDs
        projectId1 = UUID.randomUUID().toString();
        projectId2 = UUID.randomUUID().toString();

        // Initialize storage
        graphStorage.initialize().join();

        // Create graphs for both projects
        graphStorage.createProjectGraph(projectId1).join();
        graphStorage.createProjectGraph(projectId2).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up graphs
        graphStorage.deleteProjectGraph(projectId1).join();
        graphStorage.deleteProjectGraph(projectId2).join();
    }

    /**
     * T032: Test that creating project graphs creates isolated graphs.
     * 
     * Verifies:
     * - Two projects exist independently
     * - Both graphs can be queried without interference
     */
    @Test
    void testCreateProjectGraphCreatesIsolatedGraph() {
        // Verify both graphs exist
        final Boolean exists1 = graphStorage.graphExists(projectId1).join();
        final Boolean exists2 = graphStorage.graphExists(projectId2).join();

        assertTrue(exists1, "Project 1 graph should exist");
        assertTrue(exists2, "Project 2 graph should exist");

        // Verify both graphs are empty initially
        final List<Entity> entities1 = graphStorage.getAllEntities(projectId1).join();
        final List<Entity> entities2 = graphStorage.getAllEntities(projectId2).join();

        assertNotNull(entities1);
        assertNotNull(entities2);
        assertEquals(0, entities1.size(), "Project 1 graph should be empty");
        assertEquals(0, entities2.size(), "Project 2 graph should be empty");
    }

    /**
     * T033: Test that entities are isolated between projects.
     * 
     * Scenario:
     * - Insert "Apple Inc." entity in both projects
     * - Verify each project sees only its own entity
     * - Verify entities are separate (not shared)
     */
    @Test
    void testEntitiesIsolatedBetweenProjects() {
        // Create same entity in both projects
        final Entity entity1 = new Entity("Apple Inc.", "Company", "Tech company", null);
        final Entity entity2 = new Entity("Apple Inc.", "Company", "Tech company", null);

        graphStorage.upsertEntity(projectId1, entity1).join();
        graphStorage.upsertEntity(projectId2, entity2).join();

        // Verify each project has exactly one entity
        final List<Entity> entities1 = graphStorage.getAllEntities(projectId1).join();
        final List<Entity> entities2 = graphStorage.getAllEntities(projectId2).join();

        assertEquals(1, entities1.size(), "Project 1 should have 1 entity");
        assertEquals(1, entities2.size(), "Project 2 should have 1 entity");
        assertEquals("apple inc.", entities1.get(0).getEntityName().toLowerCase());
        assertEquals("apple inc.", entities2.get(0).getEntityName().toLowerCase());

        // Verify querying Project 1 entity from Project 2 returns null
        final Entity queriedFromProject2 = graphStorage.getEntity(projectId2, "Apple Inc.").join();
        assertNotNull(queriedFromProject2, "Project 2 should have its own Apple Inc.");

        // Add different entity to project 2
        final Entity entity3 = new Entity("Google Inc.", "Company", "Tech company", null);
        graphStorage.upsertEntity(projectId2, entity3).join();

        // Verify project 1 doesn't see Google
        final Entity googleInProject1 = graphStorage.getEntity(projectId1, "Google Inc.").join();
        assertEquals(null, googleInProject1, "Project 1 should not see Google Inc.");

        // Verify project 2 sees both entities
        final List<Entity> entities2Updated = graphStorage.getAllEntities(projectId2).join();
        assertEquals(2, entities2Updated.size(), "Project 2 should have 2 entities");
    }

    /**
     * T034: Test that relations are isolated between projects.
     * 
     * Scenario:
     * - Create entities A, B, C in Project 1
     * - Create entities A, B in Project 2
     * - Add relation A -> B in both projects
     * - Add relation B -> C in Project 1 only
     * - Verify relations don't leak across projects
     */
    @Test
    void testRelationsIsolatedBetweenProjects() {
        // Project 1: Create entities A, B, C
        final Entity entityA1 = new Entity("EntityA", "Person", "Person A", null);
        final Entity entityB1 = new Entity("EntityB", "Person", "Person B", null);
        final Entity entityC1 = new Entity("EntityC", "Person", "Person C", null);

        graphStorage.upsertEntities(projectId1, List.of(entityA1, entityB1, entityC1)).join();

        // Project 2: Create entities A, B
        final Entity entityA2 = new Entity("EntityA", "Person", "Person A", null);
        final Entity entityB2 = new Entity("EntityB", "Person", "Person B", null);

        graphStorage.upsertEntities(projectId2, List.of(entityA2, entityB2)).join();

        // Project 1: Add relations A -> B and B -> C
        final Relation relation1A = new Relation("EntityA", "EntityB", "A knows B", "knows", 1.0, null);
        final Relation relation1B = new Relation("EntityB", "EntityC", "B knows C", "knows", 1.0, null);

        graphStorage.upsertRelations(projectId1, List.of(relation1A, relation1B)).join();

        // Project 2: Add relation A -> B only
        final Relation relation2 = new Relation("EntityA", "EntityB", "A knows B", "knows", 1.0, null);

        graphStorage.upsertRelation(projectId2, relation2).join();

        // Verify Project 1 has 2 relations
        final List<Relation> relations1 = graphStorage.getAllRelations(projectId1).join();
        assertEquals(2, relations1.size(), "Project 1 should have 2 relations");

        // Verify Project 2 has 1 relation
        final List<Relation> relations2 = graphStorage.getAllRelations(projectId2).join();
        assertEquals(1, relations2.size(), "Project 2 should have 1 relation");

        // Verify Project 2 doesn't have B -> C relation
        final Relation bcInProject2 = graphStorage.getRelation(projectId2, "EntityB", "EntityC").join();
        assertEquals(null, bcInProject2, "Project 2 should not have B -> C relation");

        // Verify Project 1 has B -> C relation
        final Relation bcInProject1 = graphStorage.getRelation(projectId1, "EntityB", "EntityC").join();
        assertNotNull(bcInProject1, "Project 1 should have B -> C relation");
    }

    /**
     * T035: Test entity deduplication within project only.
     * 
     * Scenario:
     * - Insert "Apple Inc." twice in Project 1 (should deduplicate)
     * - Insert "Apple Inc." once in Project 2
     * - Verify Project 1 has 1 entity (deduplicated)
     * - Verify Project 2 has 1 entity (separate)
     * - Verify total entities across both projects = 2
     */
    @Test
    void testEntityDeduplicationWithinProjectOnly() {
        // Project 1: Insert Apple Inc. twice
        final Entity entity1A = new Entity("Apple Inc.", "Company", "Tech company", null);
        final Entity entity1B = new Entity("Apple Inc.", "Company", "Tech company updated", null);

        graphStorage.upsertEntity(projectId1, entity1A).join();
        graphStorage.upsertEntity(projectId1, entity1B).join();

        // Project 2: Insert Apple Inc. once
        final Entity entity2 = new Entity("Apple Inc.", "Company", "Different description", null);
        graphStorage.upsertEntity(projectId2, entity2).join();

        // Verify Project 1 has 1 deduplicated entity
        final List<Entity> entities1 = graphStorage.getAllEntities(projectId1).join();
        assertEquals(1, entities1.size(), "Project 1 should have 1 deduplicated entity");

        // Verify Project 2 has 1 separate entity
        final List<Entity> entities2 = graphStorage.getAllEntities(projectId2).join();
        assertEquals(1, entities2.size(), "Project 2 should have 1 separate entity");

        // Verify both are separate (total = 2, not 1)
        final int totalEntities = entities1.size() + entities2.size();
        assertEquals(2, totalEntities, "Total entities should be 2 (not deduplicated across projects)");
    }

    /**
     * T036: Test cross-project query returns empty.
     * 
     * Scenario:
     * - Add entities to Project A
     * - Query Project B returns nothing
     * - Verify no data leakage
     */
    @Test
    void testCrossProjectQueryReturnsEmpty() {
        // Add entities to Project 1 only
        final Entity entity1 = new Entity("Company1", "Company", "First company", null);
        final Entity entity2 = new Entity("Company2", "Company", "Second company", null);

        graphStorage.upsertEntities(projectId1, List.of(entity1, entity2)).join();

        // Add relation in Project 1
        final Relation relation = new Relation("Company1", "Company2", "Partnership", "partners", 1.0, null);
        graphStorage.upsertRelation(projectId1, relation).join();

        // Verify Project 1 has data
        final List<Entity> entities1 = graphStorage.getAllEntities(projectId1).join();
        final List<Relation> relations1 = graphStorage.getAllRelations(projectId1).join();

        assertEquals(2, entities1.size(), "Project 1 should have 2 entities");
        assertEquals(1, relations1.size(), "Project 1 should have 1 relation");

        // Verify Project 2 has no data
        final List<Entity> entities2 = graphStorage.getAllEntities(projectId2).join();
        final List<Relation> relations2 = graphStorage.getAllRelations(projectId2).join();

        assertEquals(0, entities2.size(), "Project 2 should have 0 entities");
        assertEquals(0, relations2.size(), "Project 2 should have 0 relations");

        // Verify specific queries return null/empty
        final Entity company1InProject2 = graphStorage.getEntity(projectId2, "Company1").join();
        assertEquals(null, company1InProject2, "Project 2 should not find Company1");
    }

    /**
     * T037: Test delete project removes all graph data.
     * 
     * Scenario:
     * - Create project with entities and relations
     * - Delete project graph
     * - Verify graph is removed
     * - Verify operations on deleted graph throw IllegalStateException
     */
    @Test
    void testDeleteProjectRemovesAllGraphData() {
        // Add data to Project 1
        final Entity entity1 = new Entity("EntityX", "Type", "Description", null);
        final Entity entity2 = new Entity("EntityY", "Type", "Description", null);

        graphStorage.upsertEntities(projectId1, List.of(entity1, entity2)).join();

        final Relation relation = new Relation("EntityX", "EntityY", "Related", "related", 1.0, null);
        graphStorage.upsertRelation(projectId1, relation).join();

        // Verify data exists
        final List<Entity> entitiesBefore = graphStorage.getAllEntities(projectId1).join();
        final List<Relation> relationsBefore = graphStorage.getAllRelations(projectId1).join();

        assertEquals(2, entitiesBefore.size(), "Should have 2 entities before deletion");
        assertEquals(1, relationsBefore.size(), "Should have 1 relation before deletion");

        // Delete project graph
        graphStorage.deleteProjectGraph(projectId1).join();

        // Verify graph no longer exists
        final Boolean exists = graphStorage.graphExists(projectId1).join();
        assertFalse(exists, "Project 1 graph should not exist after deletion");

        // Verify operations throw CompletionException wrapping IllegalStateException
        final CompletionException getAllEx = assertThrows(CompletionException.class, () -> {
            graphStorage.getAllEntities(projectId1).join();
        }, "getAllEntities should throw CompletionException for deleted graph");
        assertTrue(getAllEx.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException");

        final CompletionException upsertEx = assertThrows(CompletionException.class, () -> {
            graphStorage.upsertEntity(projectId1, entity1).join();
        }, "upsertEntity should throw CompletionException for deleted graph");
        assertTrue(upsertEx.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException");

        // Verify Project 2 is unaffected
        final List<Entity> entities2 = graphStorage.getAllEntities(projectId2).join();
        assertNotNull(entities2, "Project 2 should still be accessible");
    }

    /**
     * Additional test: Verify graph existence validation.
     * 
     * Tests that operations on non-existent graph throw IllegalStateException.
     */
    @Test
    void testGraphExistenceValidation() {
        final String nonExistentProjectId = UUID.randomUUID().toString();

        // Verify graph doesn't exist
        final Boolean exists = graphStorage.graphExists(nonExistentProjectId).join();
        assertFalse(exists, "Non-existent project graph should not exist");

        // Verify operations throw CompletionException wrapping IllegalStateException
        final Entity testEntity = new Entity("Test", "Type", "Description", null);

        final CompletionException upsertEx = assertThrows(CompletionException.class, () -> {
            graphStorage.upsertEntity(nonExistentProjectId, testEntity).join();
        }, "upsertEntity should throw CompletionException for non-existent graph");
        assertTrue(upsertEx.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException");

        final CompletionException getAllEx = assertThrows(CompletionException.class, () -> {
            graphStorage.getAllEntities(nonExistentProjectId).join();
        }, "getAllEntities should throw CompletionException for non-existent graph");
        assertTrue(getAllEx.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException");

        final CompletionException getEx = assertThrows(CompletionException.class, () -> {
            graphStorage.getEntity(nonExistentProjectId, "Test").join();
        }, "getEntity should throw CompletionException for non-existent graph");
        assertTrue(getEx.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException");
    }

    /**
     * Test graph stats are project-specific.
     */
    @Test
    void testGraphStatsAreProjectSpecific() {
        // Add 3 entities to Project 1
        final Entity e1 = new Entity("E1", "Type", "Desc1", null);
        final Entity e2 = new Entity("E2", "Type", "Desc2", null);
        final Entity e3 = new Entity("E3", "Type", "Desc3", null);

        graphStorage.upsertEntities(projectId1, List.of(e1, e2, e3)).join();

        // Add 2 relations to Project 1
        final Relation r1 = new Relation("E1", "E2", "R1", "related", 1.0, null);
        final Relation r2 = new Relation("E2", "E3", "R2", "related", 1.0, null);

        graphStorage.upsertRelations(projectId1, List.of(r1, r2)).join();

        // Add 1 entity to Project 2
        final Entity e4 = new Entity("E4", "Type", "Desc4", null);
        graphStorage.upsertEntity(projectId2, e4).join();

        // Get stats for both projects
        final GraphStorage.GraphStats stats1 = graphStorage.getStats(projectId1).join();
        final GraphStorage.GraphStats stats2 = graphStorage.getStats(projectId2).join();

        // Verify Project 1 stats
        assertEquals(3, stats1.entityCount(), "Project 1 should have 3 entities");
        assertEquals(2, stats1.relationCount(), "Project 1 should have 2 relations");

        // Verify Project 2 stats
        assertEquals(1, stats2.entityCount(), "Project 2 should have 1 entity");
        assertEquals(0, stats2.relationCount(), "Project 2 should have 0 relations");
    }
    
    // ===== Batch Operations Tests =====
    
    /**
     * Test getNodeDegreesBatch returns correct degrees for entities.
     * 
     * Scenario:
     * - Create entities A, B, C
     * - Create relations A -> B, A -> C, B -> C
     * - A should have degree 2 (2 outgoing)
     * - B should have degree 2 (1 incoming from A, 1 outgoing to C)
     * - C should have degree 2 (2 incoming)
     */
    @Test
    void testGetNodeDegreesBatch() {
        // Create entities
        final Entity entityA = new Entity("EntityA", "Type", "Entity A", null);
        final Entity entityB = new Entity("EntityB", "Type", "Entity B", null);
        final Entity entityC = new Entity("EntityC", "Type", "Entity C", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB, entityC)).join();
        
        // Create relations: A -> B, A -> C, B -> C
        final Relation relAB = new Relation("EntityA", "EntityB", "A to B", "related", 1.0, null);
        final Relation relAC = new Relation("EntityA", "EntityC", "A to C", "related", 1.0, null);
        final Relation relBC = new Relation("EntityB", "EntityC", "B to C", "related", 1.0, null);
        
        graphStorage.upsertRelations(projectId1, List.of(relAB, relAC, relBC)).join();
        
        // Get degrees for all entities
        final java.util.Map<String, Integer> degrees = graphStorage.getNodeDegreesBatch(
            projectId1, 
            List.of("EntityA", "EntityB", "EntityC"),
            500
        ).join();
        
        assertNotNull(degrees, "Degree map should not be null");
        assertEquals(3, degrees.size(), "Should have degrees for 3 entities");
        
        // Verify degrees (note: entity names are normalized to lowercase)
        assertEquals(2, degrees.get("entitya"), "EntityA should have degree 2");
        assertEquals(2, degrees.get("entityb"), "EntityB should have degree 2");
        assertEquals(2, degrees.get("entityc"), "EntityC should have degree 2");
    }
    
    /**
     * Test getNodeDegreesBatch with empty list returns empty map.
     */
    @Test
    void testGetNodeDegreesBatchEmptyList() {
        final java.util.Map<String, Integer> degrees = graphStorage.getNodeDegreesBatch(
            projectId1, 
            List.of(),
            500
        ).join();
        
        assertNotNull(degrees, "Degree map should not be null");
        assertTrue(degrees.isEmpty(), "Degree map should be empty for empty input");
    }
    
    /**
     * Test getNodeDegreesBatch returns 0 for non-existent entities.
     */
    @Test
    void testGetNodeDegreesBatchNonExistent() {
        // Create one entity only
        final Entity entityA = new Entity("EntityA", "Type", "Entity A", null);
        graphStorage.upsertEntity(projectId1, entityA).join();
        
        // Query degrees including non-existent entity
        final java.util.Map<String, Integer> degrees = graphStorage.getNodeDegreesBatch(
            projectId1, 
            List.of("EntityA", "NonExistent"),
            500
        ).join();
        
        assertNotNull(degrees, "Degree map should not be null");
        assertEquals(0, degrees.get("entitya"), "EntityA should have degree 0 (no relations)");
        assertEquals(0, degrees.get("nonexistent"), "NonExistent should have degree 0");
    }
    
    /**
     * Test getEntitiesMapBatch returns entities as a map.
     */
    @Test
    void testGetEntitiesMapBatch() {
        // Create entities
        final Entity entityA = new Entity("EntityA", "Person", "Person A", null);
        final Entity entityB = new Entity("EntityB", "Company", "Company B", null);
        final Entity entityC = new Entity("EntityC", "Location", "Location C", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB, entityC)).join();
        
        // Get entities as map
        final java.util.Map<String, Entity> entityMap = graphStorage.getEntitiesMapBatch(
            projectId1,
            List.of("EntityA", "EntityB"),
            1000
        ).join();
        
        assertNotNull(entityMap, "Entity map should not be null");
        assertEquals(2, entityMap.size(), "Should have 2 entities in map");
        
        // Verify entities (note: entity names are normalized to lowercase)
        assertNotNull(entityMap.get("entitya"), "Should have entitya");
        assertNotNull(entityMap.get("entityb"), "Should have entityb");
        assertEquals("person", entityMap.get("entitya").getEntityType().toLowerCase());
        assertEquals("company", entityMap.get("entityb").getEntityType().toLowerCase());
    }
    
    /**
     * Test getEntitiesMapBatch with empty list returns empty map.
     */
    @Test
    void testGetEntitiesMapBatchEmptyList() {
        final java.util.Map<String, Entity> entityMap = graphStorage.getEntitiesMapBatch(
            projectId1,
            List.of(),
            1000
        ).join();
        
        assertNotNull(entityMap, "Entity map should not be null");
        assertTrue(entityMap.isEmpty(), "Entity map should be empty for empty input");
    }
    
    /**
     * Test getEntitiesMapBatch with partial matches returns only found entities.
     */
    @Test
    void testGetEntitiesMapBatchPartialMatch() {
        // Create one entity only
        final Entity entityA = new Entity("EntityA", "Type", "Entity A", null);
        graphStorage.upsertEntity(projectId1, entityA).join();
        
        // Query for both existing and non-existing
        final java.util.Map<String, Entity> entityMap = graphStorage.getEntitiesMapBatch(
            projectId1,
            List.of("EntityA", "NonExistent"),
            1000
        ).join();
        
        assertNotNull(entityMap, "Entity map should not be null");
        assertEquals(1, entityMap.size(), "Should have only 1 entity in map");
        assertNotNull(entityMap.get("entitya"), "Should have entitya");
        assertFalse(entityMap.containsKey("nonexistent"), "Should not have nonexistent");
    }
    
    // ===== BFS Traversal Tests =====
    
    /**
     * Test traverseBFS with simple linear graph.
     * 
     * Graph: A -> B -> C
     * 
     * From A with maxDepth=2: Should return A, B, C
     * From A with maxDepth=1: Should return A, B
     */
    @Test
    void testTraverseBFSSimpleLinearGraph() {
        // Create linear graph: A -> B -> C
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        final Entity entityC = new Entity("C", "Type", "Entity C", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB, entityC)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        final Relation relBC = new Relation("B", "C", "B to C", "related", 1.0, null);
        
        graphStorage.upsertRelations(projectId1, List.of(relAB, relBC)).join();
        
        // Traverse from A with maxDepth=2 (should get all)
        final GraphStorage.GraphSubgraph result1 = graphStorage.traverseBFS(projectId1, "A", 2, 0).join();
        
        assertNotNull(result1, "Result should not be null");
        assertEquals(3, result1.entities().size(), "Should have 3 entities");
        assertEquals(2, result1.relations().size(), "Should have 2 relations");
        
        // Traverse from A with maxDepth=1 (should get A and B only)
        final GraphStorage.GraphSubgraph result2 = graphStorage.traverseBFS(projectId1, "A", 1, 0).join();
        
        assertNotNull(result2, "Result should not be null");
        assertEquals(2, result2.entities().size(), "Should have 2 entities");
        assertEquals(1, result2.relations().size(), "Should have 1 relation");
    }
    
    /**
     * Test traverseBFS with maxNodes limit.
     * 
     * Graph: A -> B, A -> C, A -> D, A -> E
     * 
     * From A with maxNodes=3: Should return only 3 entities
     */
    @Test
    void testTraverseBFSMaxNodesLimit() {
        // Create star graph: A connected to B, C, D, E
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        final Entity entityC = new Entity("C", "Type", "Entity C", null);
        final Entity entityD = new Entity("D", "Type", "Entity D", null);
        final Entity entityE = new Entity("E", "Type", "Entity E", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB, entityC, entityD, entityE)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        final Relation relAC = new Relation("A", "C", "A to C", "related", 1.0, null);
        final Relation relAD = new Relation("A", "D", "A to D", "related", 1.0, null);
        final Relation relAE = new Relation("A", "E", "A to E", "related", 1.0, null);
        
        graphStorage.upsertRelations(projectId1, List.of(relAB, relAC, relAD, relAE)).join();
        
        // Traverse from A with maxNodes=3
        final GraphStorage.GraphSubgraph result = graphStorage.traverseBFS(projectId1, "A", 10, 3).join();
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.entities().size() <= 3, "Should have at most 3 entities due to maxNodes limit");
        assertTrue(result.entities().size() >= 1, "Should have at least start entity");
    }
    
    /**
     * Test traverseBFS with depth 0 returns only start entity.
     */
    @Test
    void testTraverseBFSDepthZero() {
        // Create simple graph
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        graphStorage.upsertRelation(projectId1, relAB).join();
        
        // Traverse from A with maxDepth=0 (should only get A)
        final GraphStorage.GraphSubgraph result = graphStorage.traverseBFS(projectId1, "A", 0, 0).join();
        
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.entities().size(), "Should have 1 entity (start only)");
        assertEquals(0, result.relations().size(), "Should have 0 relations");
    }
    
    /**
     * Test traverseBFS with non-existent start entity returns empty.
     */
    @Test
    void testTraverseBFSNonExistentStart() {
        // Create simple graph
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        graphStorage.upsertEntity(projectId1, entityA).join();
        
        // Traverse from non-existent entity
        final GraphStorage.GraphSubgraph result = graphStorage.traverseBFS(projectId1, "NonExistent", 5, 0).join();
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.entities().isEmpty(), "Should have 0 entities");
        assertTrue(result.relations().isEmpty(), "Should have 0 relations");
    }
    
    /**
     * Test traverseBFS with bidirectional relationships.
     * 
     * Graph: A -> B, B -> A (bidirectional)
     * 
     * Should handle cycles correctly and not loop infinitely.
     */
    @Test
    void testTraverseBFSBidirectionalRelations() {
        // Create bidirectional graph
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        final Relation relBA = new Relation("B", "A", "B to A", "related", 1.0, null);
        
        graphStorage.upsertRelations(projectId1, List.of(relAB, relBA)).join();
        
        // Traverse from A with large depth (should not loop infinitely)
        final GraphStorage.GraphSubgraph result = graphStorage.traverseBFS(projectId1, "A", 100, 0).join();
        
        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.entities().size(), "Should have 2 entities");
        // Note: Could have 1 or 2 relations depending on deduplication
        assertTrue(result.relations().size() >= 1, "Should have at least 1 relation");
    }
    
    /**
     * Test traverseBFS with complex graph.
     * 
     * Graph:
     *   A -> B -> D
     *   A -> C -> D
     *   B -> C
     * 
     * Tests that all paths are explored and duplicates are avoided.
     */
    @Test
    void testTraverseBFSComplexGraph() {
        // Create complex graph with multiple paths
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        final Entity entityC = new Entity("C", "Type", "Entity C", null);
        final Entity entityD = new Entity("D", "Type", "Entity D", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB, entityC, entityD)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        final Relation relAC = new Relation("A", "C", "A to C", "related", 1.0, null);
        final Relation relBD = new Relation("B", "D", "B to D", "related", 1.0, null);
        final Relation relCD = new Relation("C", "D", "C to D", "related", 1.0, null);
        final Relation relBC = new Relation("B", "C", "B to C", "related", 1.0, null);
        
        graphStorage.upsertRelations(projectId1, List.of(relAB, relAC, relBD, relCD, relBC)).join();
        
        // Traverse from A with maxDepth=2 (should get all)
        final GraphStorage.GraphSubgraph result = graphStorage.traverseBFS(projectId1, "A", 2, 0).join();
        
        assertNotNull(result, "Result should not be null");
        assertEquals(4, result.entities().size(), "Should have 4 entities");
        assertEquals(5, result.relations().size(), "Should have 5 relations");
    }
    
    /**
     * Test traverse delegates to traverseBFS.
     * 
     * Verifies that the existing traverse() method works correctly
     * after being refactored to use traverseBFS.
     */
    @Test
    void testTraverseDelegatesToTraverseBFS() {
        // Create simple graph
        final Entity entityA = new Entity("A", "Type", "Entity A", null);
        final Entity entityB = new Entity("B", "Type", "Entity B", null);
        
        graphStorage.upsertEntities(projectId1, List.of(entityA, entityB)).join();
        
        final Relation relAB = new Relation("A", "B", "A to B", "related", 1.0, null);
        graphStorage.upsertRelation(projectId1, relAB).join();
        
        // Use old traverse() method
        final GraphStorage.GraphSubgraph result = graphStorage.traverse(projectId1, "A", 1).join();
        
        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.entities().size(), "Should have 2 entities");
        assertEquals(1, result.relations().size(), "Should have 1 relation");
    }
}
