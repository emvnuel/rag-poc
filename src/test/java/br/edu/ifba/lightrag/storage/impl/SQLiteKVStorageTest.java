package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SQLiteKVStorage.
 * 
 * Tests verify:
 * 1. Basic get/set operations
 * 2. Batch operations
 * 3. Key pattern matching
 * 4. Delete operations
 * 5. Existence checks
 */
class SQLiteKVStorageTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteKVStorage kvStorage;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (kvStorage != null) {
            kvStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Test basic set and get.
     */
    @Test
    void testSetAndGet() throws Exception {
        kvStorage.set("key1", "value1").join();
        
        String value = kvStorage.get("key1").join();
        assertEquals("value1", value, "Value should match");
    }

    /**
     * Test get returns null for missing key.
     */
    @Test
    void testGetMissingKey() throws Exception {
        String value = kvStorage.get("nonexistent").join();
        assertNull(value, "Should return null for missing key");
    }

    /**
     * Test set updates existing key.
     */
    @Test
    void testSetUpdatesExisting() throws Exception {
        kvStorage.set("key", "original").join();
        kvStorage.set("key", "updated").join();
        
        String value = kvStorage.get("key").join();
        assertEquals("updated", value, "Value should be updated");
    }

    /**
     * Test batch set.
     */
    @Test
    void testSetBatch() throws Exception {
        Map<String, String> entries = Map.of(
            "batch1", "value1",
            "batch2", "value2",
            "batch3", "value3"
        );
        
        kvStorage.setBatch(entries).join();
        
        Long size = kvStorage.size().join();
        assertEquals(3L, size, "Should have 3 entries");
    }

    /**
     * Test batch get.
     */
    @Test
    void testGetBatch() throws Exception {
        kvStorage.set("k1", "v1").join();
        kvStorage.set("k2", "v2").join();
        kvStorage.set("k3", "v3").join();
        
        Map<String, String> values = kvStorage.getBatch(List.of("k1", "k2", "missing")).join();
        
        assertEquals(2, values.size(), "Should return 2 found values");
        assertEquals("v1", values.get("k1"));
        assertEquals("v2", values.get("k2"));
        assertFalse(values.containsKey("missing"));
    }

    /**
     * Test delete single key.
     */
    @Test
    void testDelete() throws Exception {
        kvStorage.set("toDelete", "value").join();
        
        Boolean deleted = kvStorage.delete("toDelete").join();
        assertTrue(deleted, "Should return true for deleted");
        
        String value = kvStorage.get("toDelete").join();
        assertNull(value, "Value should be null after deletion");
    }

    /**
     * Test delete missing key returns false.
     */
    @Test
    void testDeleteMissingKey() throws Exception {
        Boolean deleted = kvStorage.delete("nonexistent").join();
        assertFalse(deleted, "Should return false for missing key");
    }

    /**
     * Test batch delete.
     */
    @Test
    void testDeleteBatch() throws Exception {
        kvStorage.set("d1", "v1").join();
        kvStorage.set("d2", "v2").join();
        kvStorage.set("d3", "v3").join();
        
        Integer deleted = kvStorage.deleteBatch(List.of("d1", "d2")).join();
        
        assertEquals(2, deleted, "Should delete 2 keys");
        assertEquals(1L, kvStorage.size().join(), "Should have 1 remaining");
    }

    /**
     * Test exists check.
     */
    @Test
    void testExists() throws Exception {
        kvStorage.set("existingKey", "value").join();
        
        Boolean exists = kvStorage.exists("existingKey").join();
        assertTrue(exists, "Should return true for existing key");
        
        Boolean notExists = kvStorage.exists("missingKey").join();
        assertFalse(notExists, "Should return false for missing key");
    }

    /**
     * Test get all keys.
     */
    @Test
    void testKeys() throws Exception {
        kvStorage.set("prefix:a", "v1").join();
        kvStorage.set("prefix:b", "v2").join();
        kvStorage.set("other", "v3").join();
        
        List<String> allKeys = kvStorage.keys().join();
        assertEquals(3, allKeys.size(), "Should have 3 keys");
    }

    /**
     * Test get keys by pattern.
     */
    @Test
    void testKeysPattern() throws Exception {
        kvStorage.set("prefix:a", "v1").join();
        kvStorage.set("prefix:b", "v2").join();
        kvStorage.set("other", "v3").join();
        
        List<String> prefixKeys = kvStorage.keys("prefix:%").join();
        assertEquals(2, prefixKeys.size(), "Should have 2 matching keys");
    }

    /**
     * Test clear removes all entries.
     */
    @Test
    void testClear() throws Exception {
        kvStorage.set("k1", "v1").join();
        kvStorage.set("k2", "v2").join();
        
        kvStorage.clear().join();
        
        Long size = kvStorage.size().join();
        assertEquals(0L, size, "Should be empty after clear");
    }

    /**
     * Test size returns correct count.
     */
    @Test
    void testSize() throws Exception {
        assertEquals(0L, kvStorage.size().join(), "Empty storage should have size 0");
        
        kvStorage.set("k1", "v1").join();
        kvStorage.set("k2", "v2").join();
        
        assertEquals(2L, kvStorage.size().join(), "Should have size 2");
    }
}
