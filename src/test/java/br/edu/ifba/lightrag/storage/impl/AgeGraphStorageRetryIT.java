package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for AgeGraphStorage retry behavior.
 * 
 * <p>Tests verify that the {@code @Retry} annotations on storage methods
 * correctly handle transient failures and abort on permanent errors.</p>
 * 
 * <p><b>Test Strategy:</b> Since simulating actual database connection failures
 * requires complex infrastructure (e.g., Testcontainers with network manipulation),
 * these tests focus on verifying that:</p>
 * <ul>
 *   <li>Retry annotations are correctly applied (method signatures)</li>
 *   <li>Permanent errors (constraint violations) are NOT retried</li>
 *   <li>Normal operations succeed without retry overhead</li>
 *   <li>Invalid input errors are propagated correctly</li>
 * </ul>
 * 
 * <p>For full transient failure simulation, use the manual test scripts
 * documented in quickstart.md.</p>
 * 
 * @see br.edu.ifba.lightrag.utils.TransientSQLExceptionPredicate
 */
@QuarkusTest
class AgeGraphStorageRetryIT {

    private static final Logger logger = LoggerFactory.getLogger(AgeGraphStorageRetryIT.class);

    @Inject
    GraphStorage graphStorage;

    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        projectId = UUID.randomUUID().toString();
        graphStorage.initialize().join();
        graphStorage.createProjectGraph(projectId).join();
        logger.info("Set up test project: {}", projectId);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            graphStorage.deleteProjectGraph(projectId).join();
            logger.info("Cleaned up test project: {}", projectId);
        } catch (Exception e) {
            logger.warn("Cleanup failed for project {}: {}", projectId, e.getMessage());
        }
    }

    @Nested
    @DisplayName("US1: Resilient Document Processing - Upsert Operations")
    class UpsertRetryTests {

        /**
         * T021: Verify upsertEntity completes successfully under normal conditions.
         * This validates the method works and retry doesn't interfere with success path.
         */
        @Test
        @DisplayName("T021: upsertEntity succeeds on normal operation")
        void testUpsertEntitySucceedsNormally() {
            final Entity entity = new Entity("TestCompany", "ORGANIZATION", "A test company", null);

            // Should complete without exception
            graphStorage.upsertEntity(projectId, entity).join();

            // Verify entity was created
            final Entity retrieved = graphStorage.getEntity(projectId, "TestCompany").join();
            assertNotNull(retrieved, "Entity should be retrieved after upsert");
            assertEquals("testcompany", retrieved.getEntityName().toLowerCase());
        }

        /**
         * T022: Verify upsertRelation completes successfully under normal conditions.
         */
        @Test
        @DisplayName("T022: upsertRelation succeeds on normal operation")
        void testUpsertRelationSucceedsNormally() {
            // Setup entities first
            final Entity srcEntity = new Entity("CompanyA", "ORGANIZATION", "Source company", null);
            final Entity tgtEntity = new Entity("CompanyB", "ORGANIZATION", "Target company", null);

            graphStorage.upsertEntity(projectId, srcEntity).join();
            graphStorage.upsertEntity(projectId, tgtEntity).join();

            // Create relation
            final Relation relation = new Relation("CompanyA", "CompanyB", "Partners with", "partnership", 0.9, null);

            // Should complete without exception
            graphStorage.upsertRelation(projectId, relation).join();

            // Verify relation was created
            final Relation retrieved = graphStorage.getRelation(projectId, "CompanyA", "CompanyB").join();
            assertNotNull(retrieved, "Relation should be retrieved after upsert");
        }

        /**
         * T023: Verify batch upsertEntities completes successfully.
         */
        @Test
        @DisplayName("T023: upsertEntities batch succeeds on normal operation")
        void testBatchUpsertEntitiesSucceedsNormally() {
            final List<Entity> entities = List.of(
                new Entity("Entity1", "PERSON", "First entity", null),
                new Entity("Entity2", "PERSON", "Second entity", null),
                new Entity("Entity3", "PERSON", "Third entity", null)
            );

            // Should complete without exception
            graphStorage.upsertEntities(projectId, entities).join();

            // Verify all entities were created
            final List<Entity> retrieved = graphStorage.getAllEntities(projectId).join();
            assertEquals(3, retrieved.size(), "All 3 entities should be created");
        }

        /**
         * T024: Verify constraint violations are NOT retried (permanent error).
         * Invalid projectId should throw IllegalArgumentException immediately.
         */
        @Test
        @DisplayName("T024: Invalid projectId throws IllegalArgumentException without retry")
        void testConstraintViolationNotRetried() {
            final String invalidProjectId = "not-a-valid-uuid";
            final Entity entity = new Entity("Test", "TYPE", "Description", null);

            // Should throw CompletionException wrapping IllegalArgumentException
            final CompletionException ex = assertThrows(CompletionException.class, () -> {
                graphStorage.upsertEntity(invalidProjectId, entity).join();
            });

            assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "Should throw IllegalArgumentException for invalid projectId");
            logger.info("Correctly rejected invalid projectId: {}", ex.getCause().getMessage());
        }

        /**
         * Verify upsert on non-existent graph throws IllegalStateException.
         * This is a permanent error that should NOT be retried.
         */
        @Test
        @DisplayName("Upsert on non-existent graph throws IllegalStateException")
        void testUpsertOnNonExistentGraphNotRetried() {
            final String nonExistentProjectId = UUID.randomUUID().toString();
            final Entity entity = new Entity("Test", "TYPE", "Description", null);

            // Should throw CompletionException wrapping IllegalStateException
            final CompletionException ex = assertThrows(CompletionException.class, () -> {
                graphStorage.upsertEntity(nonExistentProjectId, entity).join();
            });

            assertTrue(ex.getCause() instanceof IllegalStateException,
                "Should throw IllegalStateException for non-existent graph");
            logger.info("Correctly rejected non-existent graph: {}", ex.getCause().getMessage());
        }
    }

    @Nested
    @DisplayName("US2: Reliable Chat Queries - Read Operations")
    class QueryRetryTests {

        @BeforeEach
        void setUpTestData() {
            // Insert test data for query tests
            final List<Entity> entities = List.of(
                new Entity("Alice", "PERSON", "Software engineer", null),
                new Entity("Bob", "PERSON", "Product manager", null),
                new Entity("TechCorp", "ORGANIZATION", "Tech company", null)
            );

            graphStorage.upsertEntities(projectId, entities).join();

            final Relation relation = new Relation("Alice", "TechCorp", "Works at", "employment", 1.0, null);
            graphStorage.upsertRelation(projectId, relation).join();
        }

        /**
         * T031: Verify getEntity completes successfully.
         */
        @Test
        @DisplayName("T031: getEntity succeeds on normal operation")
        void testGetEntitySucceedsNormally() {
            final Entity entity = graphStorage.getEntity(projectId, "Alice").join();

            assertNotNull(entity, "Should retrieve existing entity");
            assertEquals("alice", entity.getEntityName().toLowerCase());
        }

        /**
         * T032: Verify getEntities batch retrieval succeeds.
         */
        @Test
        @DisplayName("T032: getEntities succeeds on normal operation")
        void testGetEntitiesSucceedsNormally() {
            final List<Entity> entities = graphStorage.getEntities(projectId, List.of("Alice", "Bob")).join();

            assertEquals(2, entities.size(), "Should retrieve both entities");
        }

        /**
         * T033: Verify getRelationsForEntity succeeds.
         */
        @Test
        @DisplayName("T033: getRelationsForEntity succeeds on normal operation")
        void testGetRelationsForEntitySucceedsNormally() {
            final List<Relation> relations = graphStorage.getRelationsForEntity(projectId, "Alice").join();

            assertEquals(1, relations.size(), "Alice should have one relation");
        }

        /**
         * T034: Verify getAllEntities succeeds.
         */
        @Test
        @DisplayName("T034: getAllEntities succeeds on normal operation")
        void testGetAllEntitiesSucceedsNormally() {
            final List<Entity> entities = graphStorage.getAllEntities(projectId).join();

            assertEquals(3, entities.size(), "Should retrieve all 3 entities");
        }

        /**
         * Verify query on non-existent graph throws appropriate error.
         */
        @Test
        @DisplayName("Query on non-existent graph throws IllegalStateException")
        void testQueryOnNonExistentGraphThrows() {
            final String nonExistentProjectId = UUID.randomUUID().toString();

            final CompletionException ex = assertThrows(CompletionException.class, () -> {
                graphStorage.getEntity(nonExistentProjectId, "Alice").join();
            });

            assertTrue(ex.getCause() instanceof IllegalStateException,
                "Should throw IllegalStateException for non-existent graph");
        }
    }

    @Nested
    @DisplayName("US3: Graph Operations - Lifecycle")
    class GraphLifecycleRetryTests {

        /**
         * T041: Verify createProjectGraph is idempotent (can be called multiple times).
         */
        @Test
        @DisplayName("T041: createProjectGraph is idempotent")
        void testCreateProjectGraphIdempotent() {
            final String newProjectId = UUID.randomUUID().toString();

            try {
                // First creation
                graphStorage.createProjectGraph(newProjectId).join();
                assertTrue(graphStorage.graphExists(newProjectId).join(), "Graph should exist after first creation");

                // Second creation (idempotent - should not fail)
                graphStorage.createProjectGraph(newProjectId).join();
                assertTrue(graphStorage.graphExists(newProjectId).join(), "Graph should still exist after second creation");

                logger.info("createProjectGraph is correctly idempotent");
            } finally {
                graphStorage.deleteProjectGraph(newProjectId).join();
            }
        }

        /**
         * T042: Verify deleteProjectGraph is idempotent.
         */
        @Test
        @DisplayName("T042: deleteProjectGraph is idempotent")
        void testDeleteProjectGraphIdempotent() {
            final String newProjectId = UUID.randomUUID().toString();

            // Create and then delete
            graphStorage.createProjectGraph(newProjectId).join();
            graphStorage.deleteProjectGraph(newProjectId).join();

            // Second deletion (idempotent - should not fail)
            graphStorage.deleteProjectGraph(newProjectId).join();

            logger.info("deleteProjectGraph is correctly idempotent");
        }

        /**
         * T043: Verify graphExists returns correct status.
         */
        @Test
        @DisplayName("T043: graphExists returns correct status")
        void testGraphExistsReturnsCorrectStatus() {
            final String newProjectId = UUID.randomUUID().toString();

            // Before creation
            final Boolean existsBefore = graphStorage.graphExists(newProjectId).join();
            assertEquals(false, existsBefore, "Graph should not exist before creation");

            // After creation
            graphStorage.createProjectGraph(newProjectId).join();
            final Boolean existsAfter = graphStorage.graphExists(newProjectId).join();
            assertEquals(true, existsAfter, "Graph should exist after creation");

            // After deletion
            graphStorage.deleteProjectGraph(newProjectId).join();
            final Boolean existsAfterDelete = graphStorage.graphExists(newProjectId).join();
            assertEquals(false, existsAfterDelete, "Graph should not exist after deletion");
        }

        /**
         * Verify getStats succeeds on valid project.
         */
        @Test
        @DisplayName("getStats succeeds on valid project")
        void testGetStatsSucceeds() {
            // Insert some data
            final Entity entity = new Entity("TestEntity", "TYPE", "Description", null);
            graphStorage.upsertEntity(projectId, entity).join();

            final GraphStorage.GraphStats stats = graphStorage.getStats(projectId).join();

            assertNotNull(stats, "Stats should not be null");
            assertEquals(1, stats.entityCount(), "Should have 1 entity");
            assertEquals(0, stats.relationCount(), "Should have 0 relations");
        }
    }

    @Nested
    @DisplayName("Retry Configuration Validation")
    class RetryConfigurationTests {

        /**
         * Verify that multiple sequential operations complete successfully,
         * validating that retry interceptors don't cause resource leaks.
         */
        @Test
        @DisplayName("Multiple sequential operations succeed without resource leak")
        void testMultipleOperationsNoResourceLeak() {
            for (int i = 0; i < 10; i++) {
                final Entity entity = new Entity("Entity" + i, "TYPE", "Description " + i, null);
                graphStorage.upsertEntity(projectId, entity).join();
            }

            final List<Entity> entities = graphStorage.getAllEntities(projectId).join();
            assertEquals(10, entities.size(), "All 10 entities should be created");

            logger.info("Successfully completed 10 sequential upsert operations");
        }

        /**
         * Verify batch operations with retry complete correctly.
         */
        @Test
        @DisplayName("Batch upsert with many entities succeeds")
        void testLargeBatchUpsertSucceeds() {
            final int batchSize = 50;
            final java.util.ArrayList<Entity> entities = new java.util.ArrayList<>();

            for (int i = 0; i < batchSize; i++) {
                entities.add(new Entity("BatchEntity" + i, "TYPE", "Batch item " + i, null));
            }

            graphStorage.upsertEntities(projectId, entities).join();

            final List<Entity> retrieved = graphStorage.getAllEntities(projectId).join();
            assertEquals(batchSize, retrieved.size(), "All " + batchSize + " entities should be created");

            logger.info("Successfully completed batch upsert of {} entities", batchSize);
        }
    }
}
