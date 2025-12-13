package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.ExtractionCache;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.DocStatusStorage;
import br.edu.ifba.lightrag.storage.DocStatusStorage.DocumentStatus;
import br.edu.ifba.lightrag.storage.DocStatusStorage.ProcessingStatus;
import br.edu.ifba.lightrag.storage.ExtractionCacheStorage;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphStats;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphSubgraph;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Abstract contract test base class for storage implementations.
 * 
 * <p>This class defines the behavioral contract that ALL storage backend
 * implementations must satisfy. By implementing this abstract class,
 * both SQLite and PostgreSQL backends can be validated to ensure they
 * behave identically.</p>
 * 
 * <p>Subclasses must implement the abstract methods to provide concrete
 * storage instances for each test run.</p>
 * 
 * <p>Contract tests verify:</p>
 * <ul>
 *   <li>VectorStorage: upsert, query, delete, batch operations</li>
 *   <li>GraphStorage: entity/relation CRUD, traversal, path finding</li>
 *   <li>KVStorage: set/get/delete, pattern matching</li>
 *   <li>DocStatusStorage: status lifecycle transitions</li>
 *   <li>ExtractionCacheStorage: cache storage and retrieval</li>
 *   <li>Project isolation: data separation across projects</li>
 * </ul>
 */
public abstract class StorageContractTest {

    protected String projectId;
    protected String documentId;

    /**
     * Returns the VectorStorage implementation to test.
     */
    protected abstract VectorStorage getVectorStorage();

    /**
     * Returns the GraphStorage implementation to test.
     */
    protected abstract GraphStorage getGraphStorage();

    /**
     * Returns the KVStorage implementation to test.
     */
    protected abstract KVStorage getKVStorage();

    /**
     * Returns the DocStatusStorage implementation to test.
     */
    protected abstract DocStatusStorage getDocStatusStorage();

    /**
     * Returns the ExtractionCacheStorage implementation to test.
     */
    protected abstract ExtractionCacheStorage getExtractionCacheStorage();

    /**
     * Creates a project record for testing. Override in subclasses if needed
     * for foreign key constraints. By default, does nothing (for in-memory backends).
     * 
     * @param projId the project ID
     * @throws Exception if project creation fails
     */
    protected void createProject(String projId) throws Exception {
        // Default no-op; override in subclasses with foreign key constraints
    }

    /**
     * Creates a test vector with specified dimension and base value.
     * Vector is normalized for cosine similarity.
     */
    protected float[] createTestVector(int dimension, float baseValue) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = baseValue + (i * 0.001f);
        }
        // Normalize
        float norm = 0;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) vector[i] /= norm;
        }
        return vector;
    }

    /**
     * Returns the vector dimension used by the implementation.
     * Default is 384, but implementations may override.
     */
    protected int getVectorDimension() {
        return 384;
    }

    @BeforeEach
    void setUpContract() {
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
    }

    // ========================================================================
    // VectorStorage Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("VectorStorage Contract")
    class VectorStorageContract {

        @Test
        @DisplayName("upsert and query should return stored vector")
        void testUpsertAndQuery() throws Exception {
            VectorStorage storage = getVectorStorage();
            String id = UUID.randomUUID().toString();
            float[] vector = createTestVector(getVectorDimension(), 0.5f);
            VectorMetadata metadata = new VectorMetadata("chunk", "Test content", documentId, 0, projectId);

            storage.upsert(id, vector, metadata).join();

            List<VectorSearchResult> results = storage.query(
                vector, 10, new VectorFilter("chunk", null, projectId)).join();

            assertFalse(results.isEmpty(), "Query should return results");
            assertEquals(id, results.get(0).id(), "Should return the inserted vector");
            assertTrue(results.get(0).score() > 0.9, "Same vector should have high similarity");
        }

        @Test
        @DisplayName("upsertBatch should store multiple vectors")
        void testUpsertBatch() throws Exception {
            VectorStorage storage = getVectorStorage();
            List<VectorEntry> entries = List.of(
                new VectorEntry(UUID.randomUUID().toString(), createTestVector(getVectorDimension(), 0.1f),
                    new VectorMetadata("chunk", "Content 1", documentId, 0, projectId)),
                new VectorEntry(UUID.randomUUID().toString(), createTestVector(getVectorDimension(), 0.2f),
                    new VectorMetadata("chunk", "Content 2", documentId, 1, projectId)),
                new VectorEntry(UUID.randomUUID().toString(), createTestVector(getVectorDimension(), 0.3f),
                    new VectorMetadata("chunk", "Content 3", documentId, 2, projectId))
            );

            storage.upsertBatch(entries).join();

            List<VectorSearchResult> results = storage.query(
                createTestVector(getVectorDimension(), 0.2f), 10,
                new VectorFilter("chunk", null, projectId)).join();

            assertEquals(3, results.size(), "Should return all 3 vectors");
        }

        @Test
        @DisplayName("delete should remove vector")
        void testDelete() throws Exception {
            VectorStorage storage = getVectorStorage();
            String id = UUID.randomUUID().toString();
            float[] vector = createTestVector(getVectorDimension(), 0.5f);

            storage.upsert(id, vector, new VectorMetadata("chunk", "Content", documentId, 0, projectId)).join();
            Boolean deleted = storage.delete(id).join();

            assertTrue(deleted, "Delete should return true");
            VectorEntry entry = storage.get(id).join();
            assertNull(entry, "Vector should not exist after deletion");
        }

        @Test
        @DisplayName("query should respect project isolation")
        void testProjectIsolation() throws Exception {
            VectorStorage storage = getVectorStorage();
            String project1 = UUID.randomUUID().toString();
            String project2 = UUID.randomUUID().toString();
            float[] vector = createTestVector(getVectorDimension(), 0.5f);

            // Create projects for foreign key constraints
            createProject(project1);
            createProject(project2);

            storage.upsert(UUID.randomUUID().toString(), vector,
                new VectorMetadata("chunk", "Project 1", null, 0, project1)).join();
            storage.upsert(UUID.randomUUID().toString(), vector,
                new VectorMetadata("chunk", "Project 2", null, 0, project2)).join();

            List<VectorSearchResult> p1Results = storage.query(vector, 10,
                new VectorFilter("chunk", null, project1)).join();
            List<VectorSearchResult> p2Results = storage.query(vector, 10,
                new VectorFilter("chunk", null, project2)).join();

            assertEquals(1, p1Results.size(), "Project 1 should have 1 result");
            assertEquals(1, p2Results.size(), "Project 2 should have 1 result");
            assertTrue(p1Results.get(0).metadata().content().contains("Project 1"));
            assertTrue(p2Results.get(0).metadata().content().contains("Project 2"));
        }
    }

    // ========================================================================
    // GraphStorage Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("GraphStorage Contract")
    class GraphStorageContract {

        @Test
        @DisplayName("upsertEntity and getEntity should work correctly")
        void testUpsertAndGetEntity() throws Exception {
            GraphStorage storage = getGraphStorage();
            storage.createProjectGraph(projectId).join();

            Entity entity = Entity.builder()
                .entityName("TestEntity")
                .entityType("CONCEPT")
                .description("A test entity")
                .addSourceChunkId("chunk1")
                .build();

            storage.upsertEntity(projectId, entity).join();
            Entity retrieved = storage.getEntity(projectId, "testentity").join();

            assertNotNull(retrieved, "Entity should exist");
            assertEquals("testentity", retrieved.getEntityName(), "Name should match (lowercase)");
            assertEquals("CONCEPT", retrieved.getEntityType());
        }

        @Test
        @DisplayName("upsertRelation should create relationship between entities")
        void testUpsertRelation() throws Exception {
            GraphStorage storage = getGraphStorage();
            storage.createProjectGraph(projectId).join();

            storage.upsertEntities(projectId, List.of(
                Entity.builder().entityName("EntityA").entityType("PERSON")
                    .description("Person A").addSourceChunkId("c1").build(),
                Entity.builder().entityName("EntityB").entityType("ORGANIZATION")
                    .description("Organization B").addSourceChunkId("c1").build()
            )).join();

            Relation relation = Relation.builder()
                .srcId("EntityA").tgtId("EntityB")
                .description("works at")
                .keywords("employment")
                .weight(1.0)
                .addSourceChunkId("c1")
                .build();

            storage.upsertRelation(projectId, relation).join();

            List<Relation> relations = storage.getRelationsForEntity(projectId, "entitya").join();
            assertEquals(1, relations.size(), "Should have 1 relation");
            assertEquals("works at", relations.get(0).getDescription());
        }

        @Test
        @DisplayName("traverse should find connected entities")
        void testTraverse() throws Exception {
            GraphStorage storage = getGraphStorage();
            storage.createProjectGraph(projectId).join();

            storage.upsertEntities(projectId, List.of(
                Entity.builder().entityName("Root").entityType("CONCEPT")
                    .description("Root node").addSourceChunkId("c1").build(),
                Entity.builder().entityName("Child1").entityType("CONCEPT")
                    .description("Child 1").addSourceChunkId("c1").build(),
                Entity.builder().entityName("Child2").entityType("CONCEPT")
                    .description("Child 2").addSourceChunkId("c1").build()
            )).join();

            storage.upsertRelations(projectId, List.of(
                Relation.builder().srcId("Root").tgtId("Child1")
                    .description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
                Relation.builder().srcId("Root").tgtId("Child2")
                    .description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
            )).join();

            GraphSubgraph subgraph = storage.traverse(projectId, "Root", 1).join();

            assertEquals(3, subgraph.entities().size(), "Should find all 3 entities");
            assertEquals(2, subgraph.relations().size(), "Should find both relations");
        }

        @Test
        @DisplayName("getStats should return correct counts")
        void testGetStats() throws Exception {
            GraphStorage storage = getGraphStorage();
            storage.createProjectGraph(projectId).join();

            storage.upsertEntities(projectId, List.of(
                Entity.builder().entityName("E1").entityType("CONCEPT")
                    .description("E1").addSourceChunkId("c1").build(),
                Entity.builder().entityName("E2").entityType("CONCEPT")
                    .description("E2").addSourceChunkId("c1").build()
            )).join();

            storage.upsertRelation(projectId, Relation.builder()
                .srcId("E1").tgtId("E2")
                .description("rel").keywords("kw").weight(1.0).addSourceChunkId("c1")
                .build()).join();

            GraphStats stats = storage.getStats(projectId).join();

            assertEquals(2, stats.entityCount(), "Should have 2 entities");
            assertEquals(1, stats.relationCount(), "Should have 1 relation");
        }

        @Test
        @DisplayName("project graphs should be isolated")
        void testProjectIsolation() throws Exception {
            GraphStorage storage = getGraphStorage();
            String project1 = UUID.randomUUID().toString();
            String project2 = UUID.randomUUID().toString();

            // Create projects for foreign key constraints
            createProject(project1);
            createProject(project2);

            storage.createProjectGraph(project1).join();
            storage.createProjectGraph(project2).join();

            storage.upsertEntity(project1, Entity.builder()
                .entityName("P1Entity").entityType("CONCEPT")
                .description("Project 1").addSourceChunkId("c1").build()).join();

            storage.upsertEntity(project2, Entity.builder()
                .entityName("P2Entity").entityType("CONCEPT")
                .description("Project 2").addSourceChunkId("c1").build()).join();

            List<Entity> p1Entities = storage.getAllEntities(project1).join();
            List<Entity> p2Entities = storage.getAllEntities(project2).join();

            assertEquals(1, p1Entities.size());
            assertEquals(1, p2Entities.size());
            assertEquals("p1entity", p1Entities.get(0).getEntityName());
            assertEquals("p2entity", p2Entities.get(0).getEntityName());
        }
    }

    // ========================================================================
    // KVStorage Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("KVStorage Contract")
    class KVStorageContract {

        @Test
        @DisplayName("set and get should work correctly")
        void testSetAndGet() throws Exception {
            KVStorage storage = getKVStorage();

            storage.set("test:key1", "value1").join();
            String value = storage.get("test:key1").join();

            assertEquals("value1", value);
        }

        @Test
        @DisplayName("get should return null for missing key")
        void testGetMissing() throws Exception {
            KVStorage storage = getKVStorage();

            String value = storage.get("nonexistent:key").join();

            assertNull(value);
        }

        @Test
        @DisplayName("set should update existing key")
        void testUpdate() throws Exception {
            KVStorage storage = getKVStorage();

            storage.set("update:key", "original").join();
            storage.set("update:key", "updated").join();
            String value = storage.get("update:key").join();

            assertEquals("updated", value);
        }

        @Test
        @DisplayName("delete should remove key")
        void testDelete() throws Exception {
            KVStorage storage = getKVStorage();

            storage.set("delete:key", "value").join();
            Boolean deleted = storage.delete("delete:key").join();

            assertTrue(deleted);
            assertNull(storage.get("delete:key").join());
        }

        @Test
        @DisplayName("keys with pattern should filter correctly")
        void testKeysPattern() throws Exception {
            KVStorage storage = getKVStorage();

            storage.set("prefix:a", "1").join();
            storage.set("prefix:b", "2").join();
            storage.set("other:c", "3").join();

            List<String> prefixKeys = storage.keys("prefix:%").join();

            assertEquals(2, prefixKeys.size());
        }
    }

    // ========================================================================
    // DocStatusStorage Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("DocStatusStorage Contract")
    class DocStatusStorageContract {

        @Test
        @DisplayName("setStatus and getStatus should work correctly")
        void testSetAndGetStatus() throws Exception {
            DocStatusStorage storage = getDocStatusStorage();
            String docId = UUID.randomUUID().toString();

            DocumentStatus status = DocumentStatus.pending(docId, "/path/file.pdf");
            storage.setStatus(status).join();

            DocumentStatus retrieved = storage.getStatus(docId).join();

            assertNotNull(retrieved);
            assertEquals(docId, retrieved.docId());
            assertEquals(ProcessingStatus.PENDING, retrieved.processingStatus());
        }

        @Test
        @DisplayName("status transitions should work correctly")
        void testStatusTransitions() throws Exception {
            DocStatusStorage storage = getDocStatusStorage();
            String docId = UUID.randomUUID().toString();

            // Pending -> Processing -> Completed
            DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
            storage.setStatus(pending).join();

            DocumentStatus processing = pending.asProcessing();
            storage.setStatus(processing).join();

            DocumentStatus completed = processing.asCompleted(10, 5, 3);
            storage.setStatus(completed).join();

            DocumentStatus retrieved = storage.getStatus(docId).join();
            assertEquals(ProcessingStatus.COMPLETED, retrieved.processingStatus());
            assertEquals(10, retrieved.chunkCount());
            assertEquals(5, retrieved.entityCount());
            assertEquals(3, retrieved.relationCount());
        }

        @Test
        @DisplayName("failed status should include error message")
        void testFailedStatus() throws Exception {
            DocStatusStorage storage = getDocStatusStorage();
            String docId = UUID.randomUUID().toString();

            DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
            DocumentStatus failed = pending.asFailed("Processing failed: timeout");
            storage.setStatus(failed).join();

            DocumentStatus retrieved = storage.getStatus(docId).join();
            assertEquals(ProcessingStatus.FAILED, retrieved.processingStatus());
            assertEquals("Processing failed: timeout", retrieved.errorMessage());
        }

        @Test
        @DisplayName("getStatus should return null for missing document")
        void testGetMissing() throws Exception {
            DocStatusStorage storage = getDocStatusStorage();

            DocumentStatus status = storage.getStatus("nonexistent").join();

            assertNull(status);
        }
    }

    // ========================================================================
    // ExtractionCacheStorage Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("ExtractionCacheStorage Contract")
    class ExtractionCacheStorageContract {

        @Test
        @DisplayName("store and get should work correctly")
        void testStoreAndGet() throws Exception {
            ExtractionCacheStorage storage = getExtractionCacheStorage();
            String contentHash = "hash_" + UUID.randomUUID();
            String chunkId = UUID.randomUUID().toString();

            storage.store(projectId, CacheType.ENTITY_EXTRACTION, chunkId,
                contentHash, "{\"entities\":[\"Entity1\"]}", 100).join();

            Optional<ExtractionCache> cached = storage.get(projectId,
                CacheType.ENTITY_EXTRACTION, contentHash).join();

            assertTrue(cached.isPresent());
            assertEquals("{\"entities\":[\"Entity1\"]}", cached.get().result());
            assertEquals(100, cached.get().tokensUsed());
        }

        @Test
        @DisplayName("get should return empty for missing hash")
        void testGetMissing() throws Exception {
            ExtractionCacheStorage storage = getExtractionCacheStorage();

            Optional<ExtractionCache> cached = storage.get(projectId,
                CacheType.ENTITY_EXTRACTION, "nonexistent_hash").join();

            assertFalse(cached.isPresent());
        }

        @Test
        @DisplayName("cache should be isolated by project")
        void testProjectIsolation() throws Exception {
            ExtractionCacheStorage storage = getExtractionCacheStorage();
            String project1 = UUID.randomUUID().toString();
            String project2 = UUID.randomUUID().toString();
            String contentHash = "shared_hash";

            // Create projects for foreign key constraints
            createProject(project1);
            createProject(project2);

            storage.store(project1, CacheType.ENTITY_EXTRACTION, null,
                contentHash, "project1_result", 50).join();
            storage.store(project2, CacheType.ENTITY_EXTRACTION, null,
                contentHash, "project2_result", 60).join();

            Optional<ExtractionCache> p1Cache = storage.get(project1,
                CacheType.ENTITY_EXTRACTION, contentHash).join();
            Optional<ExtractionCache> p2Cache = storage.get(project2,
                CacheType.ENTITY_EXTRACTION, contentHash).join();

            assertTrue(p1Cache.isPresent());
            assertTrue(p2Cache.isPresent());
            assertEquals("project1_result", p1Cache.get().result());
            assertEquals("project2_result", p2Cache.get().result());
        }

        @Test
        @DisplayName("deleteByProject should remove all cached entries for project")
        void testDeleteByProject() throws Exception {
            ExtractionCacheStorage storage = getExtractionCacheStorage();
            String testProject = UUID.randomUUID().toString();

            // Create project for foreign key constraints
            createProject(testProject);

            String chunk1 = UUID.randomUUID().toString();
            String chunk2 = UUID.randomUUID().toString();

            storage.store(testProject, CacheType.ENTITY_EXTRACTION, chunk1,
                "hash1", "result1", 50).join();
            storage.store(testProject, CacheType.ENTITY_EXTRACTION, chunk2,
                "hash2", "result2", 50).join();

            int deleted = storage.deleteByProject(testProject).join();

            assertEquals(2, deleted);
            assertFalse(storage.get(testProject, CacheType.ENTITY_EXTRACTION, "hash1").join().isPresent());
            assertFalse(storage.get(testProject, CacheType.ENTITY_EXTRACTION, "hash2").join().isPresent());
        }
    }
}
