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
}
