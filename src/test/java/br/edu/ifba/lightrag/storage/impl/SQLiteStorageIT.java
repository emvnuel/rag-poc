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
import java.util.Optional;
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
import br.edu.ifba.lightrag.storage.DocStatusStorage.DocumentStatus;
import br.edu.ifba.lightrag.storage.DocStatusStorage.ProcessingStatus;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphStats;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphSubgraph;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Integration tests for SQLite storage backend.
 * 
 * Verifies the complete workflow of:
 * 1. Document upload (vectors stored)
 * 2. Entity/relation extraction (graph storage)
 * 3. Query with vector similarity search
 * 4. Graph traversal
 * 5. Project isolation across all storage types
 * 
 * This test uses all SQLite storage implementations together to verify
 * they work correctly as a complete storage backend.
 */
class SQLiteStorageIT {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;
    private SQLiteExtractionCacheStorage cacheStorage;
    private SQLiteKVStorage kvStorage;
    private SQLiteDocStatusStorage docStatusStorage;
    
    private String projectId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("integration-test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations to create schema
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize all storage implementations
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        cacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        cacheStorage.initialize().join();
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
        
        docStatusStorage = new SQLiteDocStatusStorage(connectionManager);
        docStatusStorage.initialize().join();
        
        // Create test project and document
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        createProject(projectId);
        createDocument(documentId, projectId);
        
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStorage != null) vectorStorage.close();
        if (graphStorage != null) graphStorage.close();
        if (cacheStorage != null) cacheStorage.close();
        if (kvStorage != null) kvStorage.close();
        if (docStatusStorage != null) docStatusStorage.close();
        if (connectionManager != null) connectionManager.close();
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("Complete document processing workflow")
    void testDocumentProcessingWorkflow() throws Exception {
        // Step 1: Store document chunks as vectors
        String chunk1Id = UUID.randomUUID().toString();
        String chunk2Id = UUID.randomUUID().toString();
        
        vectorStorage.upsert(chunk1Id, createTestVector(384, 0.1f), 
            new VectorMetadata("chunk", "Alice is a researcher at MIT.", documentId, 0, projectId)).join();
        vectorStorage.upsert(chunk2Id, createTestVector(384, 0.2f), 
            new VectorMetadata("chunk", "Bob works with Alice on AI projects.", documentId, 1, projectId)).join();
        
        // Step 2: Extract and store entities
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("Alice").entityType("PERSON")
                .description("A researcher at MIT").addSourceChunkId(chunk1Id).build(),
            Entity.builder().entityName("MIT").entityType("ORGANIZATION")
                .description("Massachusetts Institute of Technology").addSourceChunkId(chunk1Id).build(),
            Entity.builder().entityName("Bob").entityType("PERSON")
                .description("Works on AI projects").addSourceChunkId(chunk2Id).build()
        )).join();
        
        // Step 3: Store relations
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("Alice").tgtId("MIT")
                .description("works at").keywords("employment,research").weight(1.0)
                .addSourceChunkId(chunk1Id).build(),
            Relation.builder().srcId("Bob").tgtId("Alice")
                .description("works with").keywords("collaboration,research").weight(0.8)
                .addSourceChunkId(chunk2Id).build()
        )).join();
        
        // Step 4: Cache extraction results
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, chunk1Id, 
            "hash123", "{\"entities\":[\"Alice\",\"MIT\"]}", 150).join();
        
        // Step 5: Update document status using DocumentStatus record
        DocumentStatus pending = DocumentStatus.pending(documentId, "/path/to/file.pdf");
        docStatusStorage.setStatus(pending).join();
        
        DocumentStatus processing = pending.asProcessing();
        docStatusStorage.setStatus(processing).join();
        
        DocumentStatus completed = processing.asCompleted(2, 3, 2);
        docStatusStorage.setStatus(completed).join();
        
        // Verify: Vector search finds chunks
        // VectorFilter(type, ids, projectId)
        List<VectorSearchResult> searchResults = vectorStorage.query(
            createTestVector(384, 0.15f), 10, 
            new VectorFilter("chunk", null, projectId)).join();
        assertEquals(2, searchResults.size(), "Should find both chunks");
        
        // Verify: Graph has correct entities
        GraphStats stats = graphStorage.getStats(projectId).join();
        assertEquals(3, stats.entityCount(), "Should have 3 entities");
        assertEquals(2, stats.relationCount(), "Should have 2 relations");
        
        // Verify: Graph traversal works
        GraphSubgraph subgraph = graphStorage.traverse(projectId, "Alice", 2).join();
        assertEquals(3, subgraph.entities().size(), "Traversal should reach all 3 entities");
        
        // Verify: Cache is accessible
        Optional<ExtractionCache> cached = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, "hash123").join();
        assertTrue(cached.isPresent(), "Cache entry should exist");
        
        // Verify: Document status is correct (getStatus returns DocumentStatus directly, not Optional)
        DocumentStatus status = docStatusStorage.getStatus(documentId).join();
        assertNotNull(status, "Status should exist");
        assertEquals(ProcessingStatus.COMPLETED, status.processingStatus());
    }

    @Test
    @DisplayName("Project isolation across all storage types")
    void testProjectIsolation() throws Exception {
        String project1 = UUID.randomUUID().toString();
        String project2 = UUID.randomUUID().toString();
        createProject(project1);
        createProject(project2);
        graphStorage.createProjectGraph(project1).join();
        graphStorage.createProjectGraph(project2).join();
        
        // Store data in project 1
        String chunk1 = UUID.randomUUID().toString();
        vectorStorage.upsert(chunk1, createTestVector(384, 0.5f), 
            new VectorMetadata("chunk", "Project 1 content", null, 0, project1)).join();
        graphStorage.upsertEntity(project1, 
            Entity.builder().entityName("Entity1").entityType("CONCEPT")
                .description("Project 1 entity").addSourceChunkId(chunk1).build()).join();
        cacheStorage.store(project1, CacheType.ENTITY_EXTRACTION, null, 
            "p1hash", "project1 result", 100).join();
        
        // Store data in project 2
        String chunk2 = UUID.randomUUID().toString();
        vectorStorage.upsert(chunk2, createTestVector(384, 0.6f), 
            new VectorMetadata("chunk", "Project 2 content", null, 0, project2)).join();
        graphStorage.upsertEntity(project2, 
            Entity.builder().entityName("Entity2").entityType("CONCEPT")
                .description("Project 2 entity").addSourceChunkId(chunk2).build()).join();
        cacheStorage.store(project2, CacheType.ENTITY_EXTRACTION, null, 
            "p2hash", "project2 result", 100).join();
        
        // Verify: Vector search is isolated
        // VectorFilter(type, ids, projectId)
        List<VectorSearchResult> p1Vectors = vectorStorage.query(
            createTestVector(384, 0.5f), 10, new VectorFilter(null, null, project1)).join();
        List<VectorSearchResult> p2Vectors = vectorStorage.query(
            createTestVector(384, 0.5f), 10, new VectorFilter(null, null, project2)).join();
        
        assertEquals(1, p1Vectors.size(), "Project 1 should have 1 vector");
        assertEquals(1, p2Vectors.size(), "Project 2 should have 1 vector");
        // Access content via metadata().content()
        assertTrue(p1Vectors.get(0).metadata().content().contains("Project 1"), "Should be project 1 content");
        assertTrue(p2Vectors.get(0).metadata().content().contains("Project 2"), "Should be project 2 content");
        
        // Verify: Graph is isolated
        List<Entity> p1Entities = graphStorage.getAllEntities(project1).join();
        List<Entity> p2Entities = graphStorage.getAllEntities(project2).join();
        
        assertEquals(1, p1Entities.size(), "Project 1 should have 1 entity");
        assertEquals(1, p2Entities.size(), "Project 2 should have 1 entity");
        assertEquals("entity1", p1Entities.get(0).getEntityName(), "Should be Entity1 (lowercase)");
        assertEquals("entity2", p2Entities.get(0).getEntityName(), "Should be Entity2 (lowercase)");
        
        // Verify: Cache is isolated
        Optional<ExtractionCache> p1Cache = cacheStorage.get(project1, 
            CacheType.ENTITY_EXTRACTION, "p1hash").join();
        Optional<ExtractionCache> p2Cache = cacheStorage.get(project2, 
            CacheType.ENTITY_EXTRACTION, "p2hash").join();
        Optional<ExtractionCache> crossCache = cacheStorage.get(project1, 
            CacheType.ENTITY_EXTRACTION, "p2hash").join();
        
        assertTrue(p1Cache.isPresent(), "Project 1 cache should exist");
        assertTrue(p2Cache.isPresent(), "Project 2 cache should exist");
        assertFalse(crossCache.isPresent(), "Cross-project cache should not exist");
    }

    @Test
    @DisplayName("Graph traversal with BFS and node limits")
    void testGraphTraversal() throws Exception {
        // Create a graph with multiple levels:
        // Root -> A -> A1, A2
        // Root -> B -> B1
        // Root -> C
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder().entityName("Root").entityType("CONCEPT").description("Root node").addSourceChunkId("c1").build(),
            Entity.builder().entityName("A").entityType("CONCEPT").description("Node A").addSourceChunkId("c1").build(),
            Entity.builder().entityName("B").entityType("CONCEPT").description("Node B").addSourceChunkId("c1").build(),
            Entity.builder().entityName("C").entityType("CONCEPT").description("Node C").addSourceChunkId("c1").build(),
            Entity.builder().entityName("A1").entityType("CONCEPT").description("Node A1").addSourceChunkId("c1").build(),
            Entity.builder().entityName("A2").entityType("CONCEPT").description("Node A2").addSourceChunkId("c1").build(),
            Entity.builder().entityName("B1").entityType("CONCEPT").description("Node B1").addSourceChunkId("c1").build()
        )).join();
        
        graphStorage.upsertRelations(projectId, List.of(
            Relation.builder().srcId("Root").tgtId("A").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("Root").tgtId("B").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("Root").tgtId("C").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("A").tgtId("A1").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("A").tgtId("A2").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build(),
            Relation.builder().srcId("B").tgtId("B1").description("links").keywords("kw").weight(1.0).addSourceChunkId("c1").build()
        )).join();
        
        // Test full traversal (depth 2)
        GraphSubgraph fullTraversal = graphStorage.traverse(projectId, "Root", 2).join();
        assertEquals(7, fullTraversal.entities().size(), "Full traversal should find all 7 nodes");
        assertEquals(6, fullTraversal.relations().size(), "Full traversal should find all 6 relations");
        
        // Test limited traversal (depth 1)
        GraphSubgraph depth1 = graphStorage.traverse(projectId, "Root", 1).join();
        assertEquals(4, depth1.entities().size(), "Depth 1 should find Root + A, B, C");
        
        // Test node-limited BFS
        GraphSubgraph limited = graphStorage.traverseBFS(projectId, "Root", 10, 4).join();
        assertTrue(limited.entities().size() <= 4, "Should respect node limit of 4");
        
        // Test shortest path
        List<Entity> path = graphStorage.findShortestPath(projectId, "Root", "A1").join();
        assertEquals(3, path.size(), "Path should be Root -> A -> A1");
    }

    @Test
    @DisplayName("KV storage operations")
    void testKVStorageOperations() throws Exception {
        // Test basic set/get (KVStorage uses set(), not put())
        kvStorage.set("config:key1", "value1").join();
        kvStorage.set("config:key2", "value2").join();
        kvStorage.set("other:key3", "value3").join();
        
        // get() returns String directly, null if not found
        String value1 = kvStorage.get("config:key1").join();
        assertNotNull(value1, "Key1 should exist");
        assertEquals("value1", value1, "Value should match");
        
        // Test list by pattern (uses keys(pattern), not listKeys())
        List<String> configKeys = kvStorage.keys("config:%").join();
        assertEquals(2, configKeys.size(), "Should have 2 config keys");
        
        // Test update
        kvStorage.set("config:key1", "updated").join();
        String updated = kvStorage.get("config:key1").join();
        assertEquals("updated", updated, "Value should be updated");
        
        // Test delete
        kvStorage.delete("config:key1").join();
        String deleted = kvStorage.get("config:key1").join();
        assertNull(deleted, "Key1 should be deleted");
    }

    @Test
    @DisplayName("Document status tracking")
    void testDocumentStatusTracking() throws Exception {
        String docId = UUID.randomUUID().toString();
        
        // Initially not present (getStatus returns DocumentStatus directly, null if not found)
        DocumentStatus initial = docStatusStorage.getStatus(docId).join();
        assertNull(initial, "Status should not exist initially");
        
        // Create pending and transition to processing
        DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
        docStatusStorage.setStatus(pending).join();
        
        DocumentStatus processing = pending.asProcessing();
        docStatusStorage.setStatus(processing).join();
        
        DocumentStatus processingStatus = docStatusStorage.getStatus(docId).join();
        assertNotNull(processingStatus, "Status should exist");
        assertEquals(ProcessingStatus.PROCESSING, processingStatus.processingStatus());
        
        // Set completed with counts
        DocumentStatus completed = processing.asCompleted(10, 5, 3);
        docStatusStorage.setStatus(completed).join();
        
        DocumentStatus completedStatus = docStatusStorage.getStatus(docId).join();
        assertEquals(ProcessingStatus.COMPLETED, completedStatus.processingStatus());
        assertEquals(10, completedStatus.chunkCount());
        assertEquals(5, completedStatus.entityCount());
        assertEquals(3, completedStatus.relationCount());
        
        // Test failed status
        String failedDocId = UUID.randomUUID().toString();
        DocumentStatus failedPending = DocumentStatus.pending(failedDocId, "/path/file2.pdf");
        docStatusStorage.setStatus(failedPending).join();
        
        DocumentStatus failedProcessing = failedPending.asProcessing();
        docStatusStorage.setStatus(failedProcessing).join();
        
        DocumentStatus failed = failedProcessing.asFailed("Test error message");
        docStatusStorage.setStatus(failed).join();
        
        DocumentStatus failedStatus = docStatusStorage.getStatus(failedDocId).join();
        assertEquals(ProcessingStatus.FAILED, failedStatus.processingStatus());
        assertEquals("Test error message", failedStatus.errorMessage());
    }

    @Test
    @DisplayName("Batch operations performance")
    void testBatchOperations() throws Exception {
        // Create 100 vectors in batch
        List<VectorEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(new VectorEntry(
                UUID.randomUUID().toString(),
                createTestVector(384, i * 0.01f),
                new VectorMetadata("chunk", "Content " + i, documentId, i, projectId)
            ));
        }
        
        long start = System.currentTimeMillis();
        vectorStorage.upsertBatch(entries).join();
        long duration = System.currentTimeMillis() - start;
        
        // Verify all stored
        Long count = vectorStorage.size().join();
        assertEquals(100, count, "Should have 100 vectors");
        
        // Log performance (informational)
        System.out.println("Batch insert of 100 vectors took: " + duration + "ms");
        
        // Create 50 entities in batch
        List<Entity> entities = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entities.add(Entity.builder()
                .entityName("BatchEntity" + i)
                .entityType("CONCEPT")
                .description("Description " + i)
                .addSourceChunkId("c" + i)
                .build());
        }
        
        start = System.currentTimeMillis();
        graphStorage.upsertEntities(projectId, entities).join();
        duration = System.currentTimeMillis() - start;
        
        GraphStats stats = graphStorage.getStats(projectId).join();
        assertEquals(50, stats.entityCount(), "Should have 50 entities");
        
        System.out.println("Batch insert of 50 entities took: " + duration + "ms");
    }

    // ========== Helper Methods ==========

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

    /**
     * Creates a document record to satisfy foreign key constraints.
     */
    private void createDocument(String docId, String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) " +
                "VALUES (?, ?, 'TXT', 'NOT_PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }

    /**
     * Creates a test vector with specified dimension and base value.
     */
    private float[] createTestVector(int dimension, float baseValue) {
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
}
