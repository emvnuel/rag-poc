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
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.ExtractionCache;

/**
 * Unit tests for SQLiteExtractionCacheStorage.
 * 
 * Tests verify:
 * 1. Store and retrieve extraction results
 * 2. Cache hit by content hash
 * 3. Get by chunk ID for rebuild
 * 4. Project deletion cascades
 */
class SQLiteExtractionCacheStorageTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteExtractionCacheStorage cacheStorage;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        cacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        cacheStorage.initialize().join();
        
        projectId = UUID.randomUUID().toString();
        createProject(projectId);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (cacheStorage != null) {
            cacheStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Test store and retrieve by hash.
     */
    @Test
    void testStoreAndGet() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        String contentHash = "abc123hash";
        String result = "{\"entities\": [], \"relations\": []}";
        
        String id = cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            chunkId, contentHash, result, 100).join();
        
        assertNotNull(id, "Should return cache entry ID");
        
        Optional<ExtractionCache> cached = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        
        assertTrue(cached.isPresent(), "Cache entry should be present");
        assertEquals(result, cached.get().result(), "Result should match");
    }

    /**
     * Test cache miss returns empty.
     */
    @Test
    void testCacheMiss() throws Exception {
        Optional<ExtractionCache> cached = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, "nonexistent-hash").join();
        
        assertFalse(cached.isPresent(), "Should return empty for cache miss");
    }

    /**
     * Test get by chunk ID returns all cache types.
     */
    @Test
    void testGetByChunkId() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            chunkId, "hash1", "result1", 50).join();
        cacheStorage.store(projectId, CacheType.GLEANING, 
            chunkId, "hash2", "result2", 30).join();
        
        List<ExtractionCache> results = cacheStorage.getByChunkId(projectId, chunkId).join();
        
        assertEquals(2, results.size(), "Should return 2 cache entries");
    }

    /**
     * Test delete by project removes all entries.
     */
    @Test
    void testDeleteByProject() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            chunkId, "hash1", "result1", 50).join();
        cacheStorage.store(projectId, CacheType.GLEANING, 
            chunkId, "hash2", "result2", 30).join();
        
        Integer deleted = cacheStorage.deleteByProject(projectId).join();
        
        assertEquals(2, deleted, "Should delete 2 entries");
        
        List<ExtractionCache> remaining = cacheStorage.getByChunkId(projectId, chunkId).join();
        assertTrue(remaining.isEmpty(), "Should have no remaining entries");
    }

    /**
     * Test project isolation.
     */
    @Test
    void testProjectIsolation() throws Exception {
        String project1 = UUID.randomUUID().toString();
        String project2 = UUID.randomUUID().toString();
        createProject(project1);
        createProject(project2);
        String contentHash = "same-hash";
        
        cacheStorage.store(project1, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "result1", 50).join();
        cacheStorage.store(project2, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "result2", 50).join();
        
        Optional<ExtractionCache> fromP1 = cacheStorage.get(project1, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        Optional<ExtractionCache> fromP2 = cacheStorage.get(project2, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        
        assertEquals("result1", fromP1.get().result(), "Should get project 1 result");
        assertEquals("result2", fromP2.get().result(), "Should get project 2 result");
    }

    /**
     * Test different cache types are independent.
     */
    @Test
    void testCacheTypesIndependent() throws Exception {
        String contentHash = "same-hash";
        
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "extraction-result", 50).join();
        cacheStorage.store(projectId, CacheType.SUMMARIZATION, 
            null, contentHash, "summary-result", 30).join();
        
        Optional<ExtractionCache> extraction = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        Optional<ExtractionCache> summary = cacheStorage.get(projectId, 
            CacheType.SUMMARIZATION, contentHash).join();
        
        assertEquals("extraction-result", extraction.get().result());
        assertEquals("summary-result", summary.get().result());
    }

    /**
     * Test upsert behavior (same hash replaces).
     */
    @Test
    void testUpsertReplaces() throws Exception {
        String contentHash = "unique-hash";
        
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "original", 50).join();
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "updated", 60).join();
        
        Optional<ExtractionCache> cached = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        
        assertEquals("updated", cached.get().result(), "Should have updated result");
    }

    /**
     * Test tokens used is stored correctly.
     */
    @Test
    void testTokensUsedStored() throws Exception {
        String contentHash = "token-test-hash";
        
        cacheStorage.store(projectId, CacheType.ENTITY_EXTRACTION, 
            null, contentHash, "result", 1234).join();
        
        Optional<ExtractionCache> cached = cacheStorage.get(projectId, 
            CacheType.ENTITY_EXTRACTION, contentHash).join();
        
        assertEquals(1234, cached.get().tokensUsed(), "Tokens used should match");
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
