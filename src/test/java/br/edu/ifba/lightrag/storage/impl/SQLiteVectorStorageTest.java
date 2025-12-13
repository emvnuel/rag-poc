package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Unit tests for SQLiteVectorStorage.
 * 
 * Tests verify:
 * 1. Vector upsert and retrieval operations
 * 2. Similarity search with cosine distance
 * 3. Batch operations for efficiency
 * 4. Project isolation (vectors filtered by projectId)
 * 5. Delete operations
 */
class SQLiteVectorStorageTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations to create schema
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        projectId = UUID.randomUUID().toString();
        createProject(projectId);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStorage != null) {
            vectorStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

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
     * Test basic vector upsert and retrieval.
     */
    @Test
    void testUpsertAndGet() throws Exception {
        String id = UUID.randomUUID().toString();
        float[] vector = createTestVector(384);
        VectorMetadata metadata = new VectorMetadata("chunk", "test content", null, 0, projectId);
        
        vectorStorage.upsert(id, vector, metadata).join();
        
        VectorEntry entry = vectorStorage.get(id).join();
        assertNotNull(entry, "Entry should not be null");
        assertEquals(id, entry.id(), "ID should match");
        assertEquals("chunk", entry.metadata().type(), "Type should match");
        assertEquals("test content", entry.metadata().content(), "Content should match");
    }

    /**
     * Test batch upsert operations.
     */
    @Test
    void testUpsertBatch() throws Exception {
        List<VectorEntry> entries = List.of(
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384),
                new VectorMetadata("chunk", "content 1", null, 0, projectId)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384),
                new VectorMetadata("chunk", "content 2", null, 1, projectId)),
            new VectorEntry(UUID.randomUUID().toString(), createTestVector(384),
                new VectorMetadata("chunk", "content 3", null, 2, projectId))
        );
        
        vectorStorage.upsertBatch(entries).join();
        
        Long size = vectorStorage.size().join();
        assertEquals(3L, size, "Should have 3 vectors");
    }

    /**
     * Test similarity search returns results ordered by score.
     */
    @Test
    void testQueryReturnsSimilarVectors() throws Exception {
        // Insert test vectors
        float[] baseVector = createTestVector(384);
        float[] similarVector = createSimilarVector(baseVector, 0.1f);
        float[] differentVector = createTestVector(384);
        
        vectorStorage.upsert("base", baseVector, 
            new VectorMetadata("chunk", "base content", null, 0, projectId)).join();
        vectorStorage.upsert("similar", similarVector,
            new VectorMetadata("chunk", "similar content", null, 1, projectId)).join();
        vectorStorage.upsert("different", differentVector,
            new VectorMetadata("chunk", "different content", null, 2, projectId)).join();
        
        // Query with base vector
        VectorFilter filter = new VectorFilter("chunk", null, projectId);
        List<VectorSearchResult> results = vectorStorage.query(baseVector, 3, filter).join();
        
        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Results should not be empty");
        assertEquals("base", results.get(0).id(), "Most similar should be itself");
    }

    /**
     * Test project isolation - vectors from other projects not returned.
     */
    @Test
    void testProjectIsolation() throws Exception {
        String project1 = UUID.randomUUID().toString();
        String project2 = UUID.randomUUID().toString();
        createProject(project1);
        createProject(project2);
        
        float[] vector = createTestVector(384);
        
        vectorStorage.upsert("vec1", vector,
            new VectorMetadata("chunk", "project 1 content", null, 0, project1)).join();
        vectorStorage.upsert("vec2", vector,
            new VectorMetadata("chunk", "project 2 content", null, 0, project2)).join();
        
        // Query only project 1
        VectorFilter filter = new VectorFilter("chunk", null, project1);
        List<VectorSearchResult> results = vectorStorage.query(vector, 10, filter).join();
        
        assertEquals(1, results.size(), "Should only return project 1 vectors");
        assertEquals("vec1", results.get(0).id(), "Should return correct vector");
    }

    /**
     * Test delete single vector.
     */
    @Test
    void testDelete() throws Exception {
        String id = UUID.randomUUID().toString();
        vectorStorage.upsert(id, createTestVector(384),
            new VectorMetadata("chunk", "content", null, 0, projectId)).join();
        
        Boolean deleted = vectorStorage.delete(id).join();
        assertTrue(deleted, "Should return true for deleted");
        
        VectorEntry entry = vectorStorage.get(id).join();
        assertTrue(entry == null, "Entry should be null after deletion");
    }

    /**
     * Test batch delete operations.
     */
    @Test
    void testDeleteBatch() throws Exception {
        List<String> ids = List.of(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString()
        );
        
        for (String id : ids) {
            vectorStorage.upsert(id, createTestVector(384),
                new VectorMetadata("chunk", "content", null, 0, projectId)).join();
        }
        
        Integer deleted = vectorStorage.deleteBatch(ids).join();
        assertEquals(3, deleted, "Should delete all 3 vectors");
        
        Long size = vectorStorage.size().join();
        assertEquals(0L, size, "Storage should be empty");
    }

    /**
     * Test delete entity embeddings by name.
     */
    @Test
    void testDeleteEntityEmbeddings() throws Exception {
        vectorStorage.upsert("e1", createTestVector(384),
            new VectorMetadata("entity", "Apple Inc.", null, null, projectId)).join();
        vectorStorage.upsert("e2", createTestVector(384),
            new VectorMetadata("entity", "Microsoft", null, null, projectId)).join();
        vectorStorage.upsert("c1", createTestVector(384),
            new VectorMetadata("chunk", "some chunk", null, 0, projectId)).join();
        
        Integer deleted = vectorStorage.deleteEntityEmbeddings(projectId, 
            java.util.Set.of("Apple Inc.")).join();
        
        assertEquals(1, deleted, "Should delete 1 entity embedding");
        Long size = vectorStorage.size().join();
        assertEquals(2L, size, "Should have 2 remaining vectors");
    }

    /**
     * Test get chunk IDs by document ID.
     */
    @Test
    void testGetChunkIdsByDocumentId() throws Exception {
        String docId = UUID.randomUUID().toString();
        createDocument(docId, projectId);
        
        vectorStorage.upsert("c1", createTestVector(384),
            new VectorMetadata("chunk", "chunk 1", docId, 0, projectId)).join();
        vectorStorage.upsert("c2", createTestVector(384),
            new VectorMetadata("chunk", "chunk 2", docId, 1, projectId)).join();
        
        String otherDocId = UUID.randomUUID().toString();
        createDocument(otherDocId, projectId);
        vectorStorage.upsert("c3", createTestVector(384),
            new VectorMetadata("chunk", "other doc", otherDocId, 0, projectId)).join();
        
        List<String> chunkIds = vectorStorage.getChunkIdsByDocumentId(projectId, docId).join();
        
        assertEquals(2, chunkIds.size(), "Should return 2 chunks for document");
        assertTrue(chunkIds.contains("c1"), "Should contain c1");
        assertTrue(chunkIds.contains("c2"), "Should contain c2");
    }

    /**
     * Test clear removes all vectors.
     */
    @Test
    void testClear() throws Exception {
        vectorStorage.upsert("v1", createTestVector(384),
            new VectorMetadata("chunk", "content", null, 0, projectId)).join();
        vectorStorage.upsert("v2", createTestVector(384),
            new VectorMetadata("chunk", "content", null, 1, projectId)).join();
        
        vectorStorage.clear().join();
        
        Long size = vectorStorage.size().join();
        assertEquals(0L, size, "Storage should be empty after clear");
    }

    /**
     * Test upsert updates existing vector.
     */
    @Test
    void testUpsertUpdatesExisting() throws Exception {
        String id = UUID.randomUUID().toString();
        
        vectorStorage.upsert(id, createTestVector(384),
            new VectorMetadata("chunk", "original content", null, 0, projectId)).join();
        vectorStorage.upsert(id, createTestVector(384),
            new VectorMetadata("chunk", "updated content", null, 0, projectId)).join();
        
        VectorEntry entry = vectorStorage.get(id).join();
        assertEquals("updated content", entry.metadata().content(), "Content should be updated");
        
        Long size = vectorStorage.size().join();
        assertEquals(1L, size, "Should still have only 1 vector");
    }

    // Helper methods

    /**
     * Creates a document record to satisfy foreign key constraints.
     */
    private void createDocument(String docId, String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) VALUES (?, ?, 'TXT', 'PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connectionManager.releaseWriteConnection(conn);
        }
    }
    
    private float[] createTestVector(int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) Math.random();
        }
        // Normalize
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dimension; i++) {
            vector[i] /= norm;
        }
        return vector;
    }

    private float[] createSimilarVector(float[] base, float noise) {
        float[] vector = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            vector[i] = base[i] + (float) (Math.random() * noise - noise / 2);
        }
        // Normalize
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }

    /**
     * Test that custom table name creates the correct table.
     */
    @Test
    void testCustomTableName() throws Exception {
        // Create a new storage with custom table name
        String customTableName = "embeddings";
        SQLiteVectorStorage customStorage = new SQLiteVectorStorage(connectionManager, 384, customTableName);
        customStorage.initialize().join();
        
        try {
            // Verify the table name is correctly set
            assertEquals(customTableName, customStorage.getTableName(), "Table name should be 'embeddings'");
            
            // Verify the table was created by checking if we can insert/query
            String testId = UUID.randomUUID().toString();
            customStorage.upsert(testId, createTestVector(384),
                new VectorMetadata("chunk", "test content", null, 0, projectId)).join();
            
            VectorEntry entry = customStorage.get(testId).join();
            assertNotNull(entry, "Should be able to retrieve from custom table");
            assertEquals("test content", entry.metadata().content(), "Content should match");
            
            // Verify the table exists in the database by querying sqlite_master
            Connection conn = connectionManager.getWriteConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
                stmt.setString(1, customTableName);
                try (var rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Custom table 'embeddings' should exist in database");
                }
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        } finally {
            customStorage.close();
        }
    }

    /**
     * Test that null/blank table name falls back to default.
     */
    @Test
    void testNullTableNameFallsBackToDefault() throws Exception {
        SQLiteVectorStorage storageWithNull = new SQLiteVectorStorage(connectionManager, 384, null);
        assertEquals("vectors", storageWithNull.getTableName(), "Null table name should fall back to 'vectors'");
        storageWithNull.close();
        
        SQLiteVectorStorage storageWithBlank = new SQLiteVectorStorage(connectionManager, 384, "  ");
        assertEquals("vectors", storageWithBlank.getTableName(), "Blank table name should fall back to 'vectors'");
        storageWithBlank.close();
    }
}
