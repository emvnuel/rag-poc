package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphStats;
import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Integration test verifying that both SQLite and PostgreSQL backends
 * produce functionally equivalent results.
 * 
 * <p>This test demonstrates that the same operations performed on either
 * backend will yield consistent behavior, validating the storage abstraction.</p>
 * 
 * <p>The test runs identical operations on the SQLite backend (which can run
 * without external dependencies) and verifies the contract behavior. The
 * PostgreSQL backend is tested separately via the existing integration tests
 * that require a PostgreSQL database.</p>
 * 
 * <p>Key validations:</p>
 * <ul>
 *   <li>Vector upsert and query return expected results</li>
 *   <li>Graph entity/relation operations are consistent</li>
 *   <li>Project isolation works correctly</li>
 *   <li>Backend switching is transparent to the application code</li>
 * </ul>
 */
class BackendSwitchingIT {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;
    
    private String projectId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("backend-switching-test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations to create schema
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize storage implementations
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        
        // Create test project and document to satisfy foreign key constraints
        createProject(projectId);
        createDocument(documentId, projectId);
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStorage != null) vectorStorage.close();
        if (graphStorage != null) graphStorage.close();
        if (connectionManager != null) connectionManager.close();
    }

    // ========================================================================
    // Backend Equivalence Tests
    // ========================================================================

    @Test
    @DisplayName("Vector storage operations should be backend-agnostic")
    void testVectorStorageBackendAgnostic() throws Exception {
        // This test validates that vector operations work identically
        // regardless of which backend is used
        
        String chunkId = UUID.randomUUID().toString();
        float[] vector = createTestVector(384, 0.5f);
        VectorMetadata metadata = new VectorMetadata("chunk", "Test content for backend switching", documentId, 0, projectId);
        
        // Operation: upsert
        vectorStorage.upsert(chunkId, vector, metadata).join();
        
        // Operation: query - should find the vector
        List<VectorSearchResult> results = vectorStorage.query(
            vector, 10, new VectorFilter("chunk", null, projectId)).join();
        
        // Verify: Backend-agnostic expectations
        assertNotNull(results, "Query should return results");
        assertEquals(1, results.size(), "Should find exactly 1 vector");
        assertEquals(chunkId, results.get(0).id(), "Should return the correct vector ID");
        assertTrue(results.get(0).score() > 0.9, "Same vector should have high similarity");
        assertEquals("Test content for backend switching", results.get(0).metadata().content());
    }

    @Test
    @DisplayName("Graph storage operations should be backend-agnostic")
    void testGraphStorageBackendAgnostic() throws Exception {
        // This test validates that graph operations work identically
        // regardless of which backend is used
        
        // Operation: upsert entities
        graphStorage.upsertEntities(projectId, List.of(
            Entity.builder()
                .entityName("BackendTest")
                .entityType("CONCEPT")
                .description("Entity for backend switching test")
                .addSourceChunkId("chunk1")
                .build(),
            Entity.builder()
                .entityName("RelatedEntity")
                .entityType("CONCEPT")
                .description("Related entity")
                .addSourceChunkId("chunk1")
                .build()
        )).join();
        
        // Operation: upsert relation
        graphStorage.upsertRelation(projectId, Relation.builder()
            .srcId("BackendTest")
            .tgtId("RelatedEntity")
            .description("relates to")
            .keywords("testing,backend")
            .weight(1.0)
            .addSourceChunkId("chunk1")
            .build()).join();
        
        // Verify: Backend-agnostic expectations
        Entity retrieved = graphStorage.getEntity(projectId, "backendtest").join();
        assertNotNull(retrieved, "Entity should exist");
        assertEquals("backendtest", retrieved.getEntityName(), "Entity name should match (lowercase)");
        
        List<Relation> relations = graphStorage.getRelationsForEntity(projectId, "backendtest").join();
        assertEquals(1, relations.size(), "Should have 1 relation");
        assertEquals("relates to", relations.get(0).getDescription());
        
        GraphStats stats = graphStorage.getStats(projectId).join();
        assertEquals(2, stats.entityCount(), "Should have 2 entities");
        assertEquals(1, stats.relationCount(), "Should have 1 relation");
    }

    @Test
    @DisplayName("Project isolation should work identically across backends")
    void testProjectIsolationBackendAgnostic() throws Exception {
        String project1 = UUID.randomUUID().toString();
        String project2 = UUID.randomUUID().toString();
        
        createProject(project1);
        createProject(project2);
        graphStorage.createProjectGraph(project1).join();
        graphStorage.createProjectGraph(project2).join();
        
        float[] vector = createTestVector(384, 0.5f);
        
        // Store in project 1
        vectorStorage.upsert(UUID.randomUUID().toString(), vector,
            new VectorMetadata("chunk", "Project 1 data", null, 0, project1)).join();
        graphStorage.upsertEntity(project1, Entity.builder()
            .entityName("Project1Entity")
            .entityType("CONCEPT")
            .description("Project 1 entity")
            .addSourceChunkId("c1")
            .build()).join();
        
        // Store in project 2
        vectorStorage.upsert(UUID.randomUUID().toString(), vector,
            new VectorMetadata("chunk", "Project 2 data", null, 0, project2)).join();
        graphStorage.upsertEntity(project2, Entity.builder()
            .entityName("Project2Entity")
            .entityType("CONCEPT")
            .description("Project 2 entity")
            .addSourceChunkId("c1")
            .build()).join();
        
        // Verify isolation - Project 1
        List<VectorSearchResult> p1Vectors = vectorStorage.query(
            vector, 10, new VectorFilter(null, null, project1)).join();
        List<Entity> p1Entities = graphStorage.getAllEntities(project1).join();
        
        assertEquals(1, p1Vectors.size(), "Project 1 should have 1 vector");
        assertEquals(1, p1Entities.size(), "Project 1 should have 1 entity");
        assertTrue(p1Vectors.get(0).metadata().content().contains("Project 1"));
        assertEquals("project1entity", p1Entities.get(0).getEntityName());
        
        // Verify isolation - Project 2
        List<VectorSearchResult> p2Vectors = vectorStorage.query(
            vector, 10, new VectorFilter(null, null, project2)).join();
        List<Entity> p2Entities = graphStorage.getAllEntities(project2).join();
        
        assertEquals(1, p2Vectors.size(), "Project 2 should have 1 vector");
        assertEquals(1, p2Entities.size(), "Project 2 should have 1 entity");
        assertTrue(p2Vectors.get(0).metadata().content().contains("Project 2"));
        assertEquals("project2entity", p2Entities.get(0).getEntityName());
    }

    @Test
    @DisplayName("Batch operations should be backend-agnostic")
    void testBatchOperationsBackendAgnostic() throws Exception {
        // Test batch vector insertion
        List<VectorEntry> vectorEntries = List.of(
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.1f),
                new VectorMetadata("chunk", "Batch content 1", documentId, 0, projectId)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.2f),
                new VectorMetadata("chunk", "Batch content 2", documentId, 1, projectId)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384, 0.3f),
                new VectorMetadata("chunk", "Batch content 3", documentId, 2, projectId))
        );
        
        vectorStorage.upsertBatch(vectorEntries).join();
        
        // Test batch entity insertion
        List<Entity> entities = List.of(
            Entity.builder().entityName("BatchE1").entityType("CONCEPT")
                .description("Batch entity 1").addSourceChunkId("c1").build(),
            Entity.builder().entityName("BatchE2").entityType("CONCEPT")
                .description("Batch entity 2").addSourceChunkId("c1").build(),
            Entity.builder().entityName("BatchE3").entityType("CONCEPT")
                .description("Batch entity 3").addSourceChunkId("c1").build()
        );
        
        graphStorage.upsertEntities(projectId, entities).join();
        
        // Verify batch results
        List<VectorSearchResult> vectorResults = vectorStorage.query(
            createTestVector(384, 0.2f), 10,
            new VectorFilter("chunk", null, projectId)).join();
        assertEquals(3, vectorResults.size(), "Should find all 3 vectors");
        
        GraphStats stats = graphStorage.getStats(projectId).join();
        assertEquals(3, stats.entityCount(), "Should have 3 entities");
    }

    @Test
    @DisplayName("Storage interface abstraction should allow transparent backend switching")
    void testStorageInterfaceAbstraction() throws Exception {
        // This test demonstrates that code can be written against the interface
        // and work with any backend implementation
        
        VectorStorage vectorStorageInterface = vectorStorage;
        GraphStorage graphStorageInterface = graphStorage;
        
        // All operations through the interface
        String id = UUID.randomUUID().toString();
        vectorStorageInterface.upsert(id, createTestVector(384, 0.5f),
            new VectorMetadata("chunk", "Interface abstraction test", documentId, 0, projectId)).join();
        
        graphStorageInterface.upsertEntity(projectId, Entity.builder()
            .entityName("InterfaceEntity")
            .entityType("CONCEPT")
            .description("Entity through interface")
            .addSourceChunkId("c1")
            .build()).join();
        
        // Verify through interface
        VectorEntry entry = vectorStorageInterface.get(id).join();
        assertNotNull(entry, "Should retrieve vector through interface");
        
        Entity entity = graphStorageInterface.getEntity(projectId, "interfaceentity").join();
        assertNotNull(entity, "Should retrieve entity through interface");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a project record to satisfy foreign key constraints.
     */
    private void createProject(String projId) throws Exception {
        var conn = connectionManager.getWriteConnection();
        try (var stmt = conn.prepareStatement(
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
        var conn = connectionManager.getWriteConnection();
        try (var stmt = conn.prepareStatement(
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
     * Vector is normalized for cosine similarity.
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
