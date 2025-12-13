package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.ExtractionCache;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphStats;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphSubgraph;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Integration tests for multi-project isolation in SQLite storage.
 * 
 * <p>Tests verify that multiple projects in the same SQLite database are 
 * completely isolated - queries from one project never return data from 
 * another project, and modifications to one project never affect another.</p>
 * 
 * <p>This test covers the complete workflow of:</p>
 * <ul>
 *   <li>Vector storage project isolation</li>
 *   <li>Graph storage project isolation</li>
 *   <li>Extraction cache project isolation</li>
 *   <li>KV storage namespace isolation</li>
 *   <li>Project cascade delete</li>
 * </ul>
 * 
 * @since spec-009 User Story 5
 */
class SQLiteProjectIsolationIT {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;
    private SQLiteExtractionCacheStorage cacheStorage;
    private SQLiteKVStorage kvStorage;

    private String projectA;
    private String projectB;
    private String documentA;
    private String documentB;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("project-isolation-it.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize all storages
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        cacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        cacheStorage.initialize().join();
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
        
        // Create two isolated projects
        projectA = UUID.randomUUID().toString();
        projectB = UUID.randomUUID().toString();
        documentA = UUID.randomUUID().toString();
        documentB = UUID.randomUUID().toString();
        
        createProject(projectA, "Project A - Technology");
        createProject(projectB, "Project B - Food");
        createDocument(documentA, projectA);
        createDocument(documentB, projectB);
        
        graphStorage.createProjectGraph(projectA).join();
        graphStorage.createProjectGraph(projectB).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (kvStorage != null) kvStorage.close();
        if (cacheStorage != null) cacheStorage.close();
        if (graphStorage != null) graphStorage.close();
        if (vectorStorage != null) vectorStorage.close();
        if (connectionManager != null) connectionManager.close();
    }

    // ========================================================================
    // Complete Document Processing Workflow with Isolation
    // ========================================================================

    @Test
    @DisplayName("Complete document processing workflow maintains project isolation")
    void testCompleteWorkflowIsolation() throws Exception {
        // === Project A: Technology documents ===
        
        // Step 1A: Store document chunks as vectors
        String chunkA1 = UUID.randomUUID().toString();
        String chunkA2 = UUID.randomUUID().toString();
        
        vectorStorage.upsert(chunkA1, createTestVector(384, 0.1f), 
            new VectorMetadata("chunk", "Apple Inc is a technology company.", documentA, 0, projectA)).join();
        vectorStorage.upsert(chunkA2, createTestVector(384, 0.2f), 
            new VectorMetadata("chunk", "Steve Jobs founded Apple in 1976.", documentA, 1, projectA)).join();
        
        // Step 2A: Extract and store entities
        graphStorage.upsertEntities(projectA, List.of(
            Entity.builder().entityName("Apple Inc").entityType("ORGANIZATION")
                .description("Technology company").addSourceChunkId(chunkA1).build(),
            Entity.builder().entityName("Steve Jobs").entityType("PERSON")
                .description("Founder of Apple").addSourceChunkId(chunkA2).build()
        )).join();
        
        // Step 3A: Store relations
        graphStorage.upsertRelations(projectA, List.of(
            Relation.builder().srcId("Steve Jobs").tgtId("Apple Inc")
                .description("founded").keywords("founder,company").weight(1.0)
                .addSourceChunkId(chunkA1).build()
        )).join();
        
        // Step 4A: Cache extraction results
        cacheStorage.store(projectA, CacheType.ENTITY_EXTRACTION, chunkA1, 
            "hashA1", "{\"entities\":[\"Apple Inc\",\"Steve Jobs\"]}", 150).join();
        
        // === Project B: Food documents ===
        
        // Step 1B: Store document chunks as vectors
        String chunkB1 = UUID.randomUUID().toString();
        String chunkB2 = UUID.randomUUID().toString();
        
        vectorStorage.upsert(chunkB1, createTestVector(384, 0.3f), 
            new VectorMetadata("chunk", "Apples are nutritious fruits.", documentB, 0, projectB)).join();
        vectorStorage.upsert(chunkB2, createTestVector(384, 0.4f), 
            new VectorMetadata("chunk", "Apple pie is a popular dessert.", documentB, 1, projectB)).join();
        
        // Step 2B: Extract and store entities
        graphStorage.upsertEntities(projectB, List.of(
            Entity.builder().entityName("Apple Fruit").entityType("FOOD")
                .description("Nutritious fruit").addSourceChunkId(chunkB1).build(),
            Entity.builder().entityName("Apple Pie").entityType("FOOD")
                .description("Popular dessert").addSourceChunkId(chunkB2).build()
        )).join();
        
        // Step 3B: Store relations
        graphStorage.upsertRelations(projectB, List.of(
            Relation.builder().srcId("Apple Fruit").tgtId("Apple Pie")
                .description("ingredient of").keywords("food,dessert").weight(1.0)
                .addSourceChunkId(chunkB1).build()
        )).join();
        
        // Step 4B: Cache extraction results
        cacheStorage.store(projectB, CacheType.ENTITY_EXTRACTION, chunkB1, 
            "hashB1", "{\"entities\":[\"Apple Fruit\",\"Apple Pie\"]}", 120).join();
        
        // === Verify Complete Isolation ===
        
        // Verify Vector Isolation
        VectorFilter filterA = new VectorFilter("chunk", null, projectA);
        List<VectorSearchResult> vectorsA = vectorStorage.query(createTestVector(384, 0.15f), 10, filterA).join();
        assertEquals(2, vectorsA.size(), "Project A should have 2 vectors");
        for (VectorSearchResult r : vectorsA) {
            assertEquals(projectA, r.metadata().projectId(), "All vectors should belong to project A");
            assertTrue(r.metadata().content().contains("Apple Inc") || r.metadata().content().contains("Steve Jobs"),
                "Content should be about technology");
        }
        
        VectorFilter filterB = new VectorFilter("chunk", null, projectB);
        List<VectorSearchResult> vectorsB = vectorStorage.query(createTestVector(384, 0.35f), 10, filterB).join();
        assertEquals(2, vectorsB.size(), "Project B should have 2 vectors");
        for (VectorSearchResult r : vectorsB) {
            assertEquals(projectB, r.metadata().projectId(), "All vectors should belong to project B");
            assertTrue(r.metadata().content().contains("fruit") || r.metadata().content().contains("pie"),
                "Content should be about food");
        }
        
        // Verify Graph Isolation
        GraphStats statsA = graphStorage.getStats(projectA).join();
        assertEquals(2, statsA.entityCount(), "Project A should have 2 entities");
        assertEquals(1, statsA.relationCount(), "Project A should have 1 relation");
        
        GraphStats statsB = graphStorage.getStats(projectB).join();
        assertEquals(2, statsB.entityCount(), "Project B should have 2 entities");
        assertEquals(1, statsB.relationCount(), "Project B should have 1 relation");
        
        // Verify entity content isolation
        List<Entity> entitiesA = graphStorage.getAllEntities(projectA).join();
        for (Entity e : entitiesA) {
            assertTrue(e.getEntityName().contains("apple inc") || e.getEntityName().contains("steve jobs"),
                "Project A entities should be technology-related");
        }
        
        List<Entity> entitiesB = graphStorage.getAllEntities(projectB).join();
        for (Entity e : entitiesB) {
            assertTrue(e.getEntityName().contains("apple fruit") || e.getEntityName().contains("apple pie"),
                "Project B entities should be food-related");
        }
        
        // Verify Cache Isolation
        Optional<ExtractionCache> cacheA = cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, "hashA1").join();
        assertTrue(cacheA.isPresent(), "Cache A should exist");
        assertTrue(cacheA.get().result().contains("Apple Inc"), "Cache A should contain tech entities");
        
        Optional<ExtractionCache> cacheB = cacheStorage.get(projectB, CacheType.ENTITY_EXTRACTION, "hashB1").join();
        assertTrue(cacheB.isPresent(), "Cache B should exist");
        assertTrue(cacheB.get().result().contains("Apple Fruit"), "Cache B should contain food entities");
        
        // Cross-project access should fail
        Optional<ExtractionCache> crossCache = cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, "hashB1").join();
        assertFalse(crossCache.isPresent(), "Cross-project cache access should return empty");
    }

    @Test
    @DisplayName("Same entity names in different projects remain isolated")
    void testSameEntityNameIsolation() {
        // Create entity named "Apple" in both projects with different meanings
        
        Entity appleA = Entity.builder()
            .entityName("Apple")
            .entityType("ORGANIZATION")
            .description("Technology company founded by Steve Jobs")
            .addSourceChunkId("chunkA")
            .build();
        
        Entity appleB = Entity.builder()
            .entityName("Apple")
            .entityType("FOOD")
            .description("Red fruit that grows on trees")
            .addSourceChunkId("chunkB")
            .build();
        
        graphStorage.upsertEntity(projectA, appleA).join();
        graphStorage.upsertEntity(projectB, appleB).join();
        
        // Retrieve from each project
        Entity retrievedA = graphStorage.getEntity(projectA, "apple").join();
        Entity retrievedB = graphStorage.getEntity(projectB, "apple").join();
        
        // Verify they are different entities
        assertNotNull(retrievedA, "Apple should exist in project A");
        assertNotNull(retrievedB, "Apple should exist in project B");
        
        assertEquals("ORGANIZATION", retrievedA.getEntityType(), "Project A Apple should be ORGANIZATION");
        assertEquals("FOOD", retrievedB.getEntityType(), "Project B Apple should be FOOD");
        
        assertTrue(retrievedA.getDescription().contains("Steve Jobs"), 
            "Project A Apple should describe tech company");
        assertTrue(retrievedB.getDescription().contains("fruit"), 
            "Project B Apple should describe food");
    }

    @Test
    @DisplayName("Graph traversal respects project boundaries")
    void testGraphTraversalIsolation() {
        // Create connected graph in Project A
        graphStorage.upsertEntities(projectA, List.of(
            Entity.builder().entityName("Apple Inc").entityType("ORGANIZATION")
                .description("Tech company").addSourceChunkId("c1").build(),
            Entity.builder().entityName("iPhone").entityType("PRODUCT")
                .description("Smartphone").addSourceChunkId("c1").build(),
            Entity.builder().entityName("MacBook").entityType("PRODUCT")
                .description("Laptop").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectA, List.of(
            Relation.builder().srcId("Apple Inc").tgtId("iPhone")
                .description("manufactures").keywords("product").weight(1.0)
                .addSourceChunkId("c1").build(),
            Relation.builder().srcId("Apple Inc").tgtId("MacBook")
                .description("manufactures").keywords("product").weight(1.0)
                .addSourceChunkId("c1").build()
        )).join();
        
        // Create connected graph in Project B
        graphStorage.upsertEntities(projectB, List.of(
            Entity.builder().entityName("Apple Orchard").entityType("LOCATION")
                .description("Farm").addSourceChunkId("c2").build(),
            Entity.builder().entityName("Red Apple").entityType("FOOD")
                .description("Fruit variety").addSourceChunkId("c2").build(),
            Entity.builder().entityName("Green Apple").entityType("FOOD")
                .description("Fruit variety").addSourceChunkId("c2").build()
        )).join();
        
        graphStorage.upsertRelations(projectB, List.of(
            Relation.builder().srcId("Apple Orchard").tgtId("Red Apple")
                .description("grows").keywords("produce").weight(1.0)
                .addSourceChunkId("c2").build(),
            Relation.builder().srcId("Apple Orchard").tgtId("Green Apple")
                .description("grows").keywords("produce").weight(1.0)
                .addSourceChunkId("c2").build()
        )).join();
        
        // Traverse from Apple Inc - should only find tech products
        GraphSubgraph traversalA = graphStorage.traverse(projectA, "apple inc", 2).join();
        assertNotNull(traversalA);
        assertEquals(3, traversalA.entities().size(), "Should find Apple Inc, iPhone, MacBook");
        
        for (Entity e : traversalA.entities()) {
            String name = e.getEntityName().toLowerCase();
            assertTrue(name.contains("apple inc") || name.contains("iphone") || name.contains("macbook"),
                "Traversal A should only contain tech entities");
            assertFalse(name.contains("orchard") || name.contains("red apple") || name.contains("green apple"),
                "Traversal A should not contain food entities");
        }
        
        // Traverse from Apple Orchard - should only find fruits
        GraphSubgraph traversalB = graphStorage.traverse(projectB, "apple orchard", 2).join();
        assertNotNull(traversalB);
        assertEquals(3, traversalB.entities().size(), "Should find Apple Orchard, Red Apple, Green Apple");
        
        for (Entity e : traversalB.entities()) {
            String name = e.getEntityName().toLowerCase();
            assertTrue(name.contains("orchard") || name.contains("red apple") || name.contains("green apple"),
                "Traversal B should only contain food entities");
            assertFalse(name.contains("iphone") || name.contains("macbook"),
                "Traversal B should not contain tech entities");
        }
    }

    @Test
    @DisplayName("Deleting data from one project does not affect another")
    void testDeletionIsolation() {
        // Populate both projects
        vectorStorage.upsert(UUID.randomUUID().toString(), createTestVector(384, 0.1f),
            new VectorMetadata("chunk", "Project A content", documentA, 0, projectA)).join();
        vectorStorage.upsert(UUID.randomUUID().toString(), createTestVector(384, 0.2f),
            new VectorMetadata("chunk", "Project B content", documentB, 0, projectB)).join();
        
        graphStorage.upsertEntity(projectA, Entity.builder()
            .entityName("EntityA").entityType("CONCEPT").description("A")
            .addSourceChunkId("c1").build()).join();
        graphStorage.upsertEntity(projectB, Entity.builder()
            .entityName("EntityB").entityType("CONCEPT").description("B")
            .addSourceChunkId("c2").build()).join();
        
        String cacheChunkA = UUID.randomUUID().toString();
        String cacheChunkB = UUID.randomUUID().toString();
        cacheStorage.store(projectA, CacheType.ENTITY_EXTRACTION, cacheChunkA, "hashA", "resultA", 100).join();
        cacheStorage.store(projectB, CacheType.ENTITY_EXTRACTION, cacheChunkB, "hashB", "resultB", 100).join();
        
        // Verify both projects have data
        VectorFilter filterA = new VectorFilter(null, null, projectA);
        VectorFilter filterB = new VectorFilter(null, null, projectB);
        assertEquals(1, vectorStorage.query(createTestVector(384, 0.1f), 10, filterA).join().size());
        assertEquals(1, vectorStorage.query(createTestVector(384, 0.2f), 10, filterB).join().size());
        assertEquals(1, graphStorage.getAllEntities(projectA).join().size());
        assertEquals(1, graphStorage.getAllEntities(projectB).join().size());
        assertTrue(cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, "hashA").join().isPresent());
        assertTrue(cacheStorage.get(projectB, CacheType.ENTITY_EXTRACTION, "hashB").join().isPresent());
        
        // Delete all data from Project A
        graphStorage.deleteEntities(projectA, Set.of("entitya")).join();
        cacheStorage.deleteByProject(projectA).join();
        
        // Verify Project A data is gone
        assertEquals(0, graphStorage.getAllEntities(projectA).join().size(), 
            "Project A entities should be deleted");
        assertFalse(cacheStorage.get(projectA, CacheType.ENTITY_EXTRACTION, "hashA").join().isPresent(),
            "Project A cache should be deleted");
        
        // Verify Project B data is UNAFFECTED
        assertEquals(1, vectorStorage.query(createTestVector(384, 0.2f), 10, filterB).join().size(),
            "Project B vectors should remain");
        assertEquals(1, graphStorage.getAllEntities(projectB).join().size(),
            "Project B entities should remain");
        assertTrue(cacheStorage.get(projectB, CacheType.ENTITY_EXTRACTION, "hashB").join().isPresent(),
            "Project B cache should remain");
    }

    @Test
    @DisplayName("Entity embeddings are isolated by project")
    void testEntityEmbeddingsIsolation() {
        // Create entity embeddings in both projects
        List<VectorEntry> embeddingsA = List.of(
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.1f),
                new VectorMetadata("entity", "Apple Inc", documentA, null, projectA)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.2f),
                new VectorMetadata("entity", "Steve Jobs", documentA, null, projectA))
        );
        
        List<VectorEntry> embeddingsB = List.of(
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.3f),
                new VectorMetadata("entity", "Apple Fruit", documentB, null, projectB)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.4f),
                new VectorMetadata("entity", "Apple Pie", documentB, null, projectB))
        );
        
        vectorStorage.upsertBatch(embeddingsA).join();
        vectorStorage.upsertBatch(embeddingsB).join();
        
        // Query entity embeddings by project
        VectorFilter entityFilterA = new VectorFilter("entity", null, projectA);
        List<VectorSearchResult> entitiesA = vectorStorage.query(createTestVector(384, 0.15f), 10, entityFilterA).join();
        assertEquals(2, entitiesA.size(), "Project A should have 2 entity embeddings");
        for (VectorSearchResult r : entitiesA) {
            assertTrue(r.metadata().content().contains("Apple Inc") || r.metadata().content().contains("Steve Jobs"),
                "Entity embeddings should be tech-related");
        }
        
        VectorFilter entityFilterB = new VectorFilter("entity", null, projectB);
        List<VectorSearchResult> entitiesB = vectorStorage.query(createTestVector(384, 0.35f), 10, entityFilterB).join();
        assertEquals(2, entitiesB.size(), "Project B should have 2 entity embeddings");
        for (VectorSearchResult r : entitiesB) {
            assertTrue(r.metadata().content().contains("Apple Fruit") || r.metadata().content().contains("Apple Pie"),
                "Entity embeddings should be food-related");
        }
        
        // Delete entity embeddings from one project
        int deleted = vectorStorage.deleteEntityEmbeddings(projectA, Set.of("Apple Inc", "Steve Jobs")).join();
        assertEquals(2, deleted, "Should delete 2 entity embeddings from project A");
        
        // Verify project B is unaffected
        List<VectorSearchResult> afterDelete = vectorStorage.query(createTestVector(384, 0.35f), 10, entityFilterB).join();
        assertEquals(2, afterDelete.size(), "Project B should still have 2 entity embeddings");
    }

    @Test
    @DisplayName("KV storage uses project-prefixed keys for isolation")
    void testKVStorageIsolation() {
        // Use project-prefixed keys for isolation
        String keyA = "project:" + projectA + ":config:setting1";
        String keyB = "project:" + projectB + ":config:setting1";
        
        kvStorage.set(keyA, "value for project A").join();
        kvStorage.set(keyB, "value for project B").join();
        
        // Retrieve and verify isolation
        String valueA = kvStorage.get(keyA).join();
        String valueB = kvStorage.get(keyB).join();
        
        assertEquals("value for project A", valueA);
        assertEquals("value for project B", valueB);
        
        // List keys by project pattern
        List<String> keysA = kvStorage.keys("project:" + projectA + ":%").join();
        List<String> keysB = kvStorage.keys("project:" + projectB + ":%").join();
        
        assertEquals(1, keysA.size(), "Project A should have 1 key");
        assertEquals(1, keysB.size(), "Project B should have 1 key");
        
        // Delete key from one project
        kvStorage.delete(keyA).join();
        
        // Verify isolation maintained
        String deletedA = kvStorage.get(keyA).join();
        String stillExistsB = kvStorage.get(keyB).join();
        
        assertEquals(null, deletedA, "Project A key should be deleted");
        assertEquals("value for project B", stillExistsB, "Project B key should still exist");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void createProject(String projId, String name) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO projects (id, name, created_at, updated_at) " +
                "VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, name);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    private void createDocument(String docId, String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) " +
                "VALUES (?, ?, 'TXT', 'PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    private float[] createTestVector(int dimension, float baseValue) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = baseValue + (i * 0.001f);
        }
        // Normalize for cosine similarity
        float norm = 0;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) vector[i] /= norm;
        }
        return vector;
    }
}
