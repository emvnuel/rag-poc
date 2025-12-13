package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;

/**
 * Tests for edge deployment configuration of SQLite storage.
 * 
 * <p>Validates that edge deployment mode provides appropriate
 * low-memory settings for resource-constrained devices.</p>
 * 
 * @since spec-009
 */
class SQLiteEdgeDeploymentTest {

    @TempDir
    Path tempDir;

    // ========================================================================
    // SQLiteConnectionManager Edge Configuration Tests
    // ========================================================================

    @Nested
    @DisplayName("SQLiteConnectionManager Edge Configuration")
    class ConnectionManagerEdgeTests {

        @Test
        @DisplayName("forEdgeDeployment() should create low-memory configuration")
        void testForEdgeDeploymentConfiguration() throws Exception {
            Path dbPath = tempDir.resolve("edge.db");
            SQLiteConnectionManager manager = SQLiteConnectionManager.forEdgeDeployment(dbPath.toString());
            
            try {
                assertNotNull(manager);
                assertEquals(dbPath.toString(), manager.getDatabasePath());
                assertTrue(manager.isWalModeEnabled(), "WAL should be enabled for edge");
                assertEquals(-500, manager.getCacheSize(), "Cache should be 500KB for edge");
                assertEquals(0L, manager.getMmapSize(), "MMAP should be disabled for edge");
                assertTrue(manager.isTempStoreFile(), "Temp store should use file for edge");
            } finally {
                manager.close();
            }
        }

        @Test
        @DisplayName("Edge configuration should apply correct pragmas")
        void testEdgePragmasApplied() throws Exception {
            Path dbPath = tempDir.resolve("edge-pragmas.db");
            SQLiteConnectionManager manager = SQLiteConnectionManager.forEdgeDeployment(dbPath.toString());
            
            try {
                Connection conn = manager.createConnection();
                
                // Check mmap_size pragma
                try (PreparedStatement stmt = conn.prepareStatement("PRAGMA mmap_size");
                     ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0L, rs.getLong(1), "MMAP should be 0 for edge");
                }
                
                // Check temp_store pragma (0=default, 1=FILE, 2=MEMORY)
                try (PreparedStatement stmt = conn.prepareStatement("PRAGMA temp_store");
                     ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Temp store should be FILE for edge");
                }
                
                // Check journal_mode pragma
                try (PreparedStatement stmt = conn.prepareStatement("PRAGMA journal_mode");
                     ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("wal", rs.getString(1).toLowerCase(), "Journal should be WAL");
                }
                
                conn.close();
            } finally {
                manager.close();
            }
        }

        @Test
        @DisplayName("Default configuration should have standard settings")
        void testDefaultConfiguration() throws Exception {
            Path dbPath = tempDir.resolve("default.db");
            SQLiteConnectionManager manager = new SQLiteConnectionManager(dbPath.toString());
            
            try {
                assertEquals(-2000, manager.getCacheSize(), "Default cache should be 2MB");
                assertEquals(268435456L, manager.getMmapSize(), "Default MMAP should be 256MB");
                assertFalse(manager.isTempStoreFile(), "Default temp store should be MEMORY");
            } finally {
                manager.close();
            }
        }

        @Test
        @DisplayName("Custom configuration constructor should work correctly")
        void testCustomConfiguration() throws Exception {
            Path dbPath = tempDir.resolve("custom.db");
            SQLiteConnectionManager manager = new SQLiteConnectionManager(
                dbPath.toString(),
                Duration.ofSeconds(10),
                true,
                3,
                -1000,  // 1MB cache
                1024 * 1024,  // 1MB mmap
                true
            );
            
            try {
                assertEquals(Duration.ofSeconds(10), manager.getBusyTimeout());
                assertTrue(manager.isWalModeEnabled());
                assertEquals(-1000, manager.getCacheSize());
                assertEquals(1024 * 1024, manager.getMmapSize());
                assertTrue(manager.isTempStoreFile());
            } finally {
                manager.close();
            }
        }
    }

    // ========================================================================
    // SQLiteVectorStorage Edge Configuration Tests
    // ========================================================================

    @Nested
    @DisplayName("SQLiteVectorStorage Edge Configuration")
    class VectorStorageEdgeTests {

        private SQLiteConnectionManager connectionManager;
        private String projectId;
        private String documentId;

        @BeforeEach
        void setUp() throws Exception {
            Path dbPath = tempDir.resolve("vector-edge.db");
            connectionManager = SQLiteConnectionManager.forEdgeDeployment(dbPath.toString());
            
            // Run migrations
            SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
            migrator.migrateToLatest(connectionManager.createConnection());
            
            projectId = UUID.randomUUID().toString();
            documentId = UUID.randomUUID().toString();
            createTestData();
        }

        @AfterEach
        void tearDown() {
            if (connectionManager != null) {
                connectionManager.close();
            }
        }

        private void createTestData() throws Exception {
            Connection conn = connectionManager.getWriteConnection();
            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
                    stmt.setString(1, projectId);
                    stmt.setString(2, "Edge Test Project");
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO documents (id, project_id, type, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'TXT', 'PROCESSED', datetime('now'), datetime('now'))")) {
                    stmt.setString(1, documentId);
                    stmt.setString(2, projectId);
                    stmt.executeUpdate();
                }
            } finally {
                connectionManager.releaseWriteConnection(conn);
            }
        }

        @Test
        @DisplayName("forEdgeDeployment() should create storage with small batch size")
        void testForEdgeDeploymentBatchSize() throws Exception {
            SQLiteVectorStorage storage = SQLiteVectorStorage.forEdgeDeployment(connectionManager, 384);
            storage.initialize().join();
            
            try {
                assertEquals(100, storage.getBatchChunkSize(), "Edge batch size should be 100");
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("Default storage should have standard batch size")
        void testDefaultBatchSize() throws Exception {
            SQLiteVectorStorage storage = new SQLiteVectorStorage(connectionManager, 384);
            storage.initialize().join();
            
            try {
                assertEquals(500, storage.getBatchChunkSize(), "Default batch size should be 500");
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("Custom batch size should be configurable")
        void testCustomBatchSize() throws Exception {
            SQLiteVectorStorage storage = new SQLiteVectorStorage(connectionManager, 384, "vectors", 50);
            storage.initialize().join();
            
            try {
                assertEquals(50, storage.getBatchChunkSize(), "Custom batch size should be 50");
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("Edge batch processing should commit in chunks")
        void testEdgeBatchProcessingCommitsInChunks() throws Exception {
            SQLiteVectorStorage storage = SQLiteVectorStorage.forEdgeDeployment(connectionManager, 384);
            storage.initialize().join();
            
            try {
                // Insert 250 vectors (should process in 3 chunks: 100, 100, 50)
                List<VectorEntry> entries = new ArrayList<>();
                for (int i = 0; i < 250; i++) {
                    String id = UUID.randomUUID().toString();
                    float[] vector = new float[384];
                    for (int j = 0; j < 384; j++) {
                        vector[j] = (float) Math.random();
                    }
                    VectorMetadata metadata = new VectorMetadata(
                        "chunk", "Content " + i, documentId, i, projectId
                    );
                    entries.add(new VectorEntry(id, vector, metadata));
                }
                
                storage.upsertBatch(entries).join();
                
                // Verify all vectors were stored
                long count = storage.size().join();
                assertEquals(250, count, "All 250 vectors should be stored");
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("Edge storage should work with vector queries")
        void testEdgeVectorQuery() throws Exception {
            SQLiteVectorStorage storage = SQLiteVectorStorage.forEdgeDeployment(connectionManager, 384);
            storage.initialize().join();
            
            try {
                // Insert some test vectors
                List<VectorEntry> entries = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    String id = UUID.randomUUID().toString();
                    float[] vector = new float[384];
                    for (int j = 0; j < 384; j++) {
                        vector[j] = (float) i / 50 + j * 0.001f;
                    }
                    // Normalize
                    float norm = 0;
                    for (float v : vector) norm += v * v;
                    norm = (float) Math.sqrt(norm);
                    for (int j = 0; j < 384; j++) vector[j] /= norm;
                    
                    VectorMetadata metadata = new VectorMetadata(
                        "chunk", "Content " + i, documentId, i, projectId
                    );
                    entries.add(new VectorEntry(id, vector, metadata));
                }
                storage.upsertBatch(entries).join();
                
                // Query
                float[] queryVector = (float[]) entries.get(25).vector();
                VectorFilter filter = new VectorFilter(null, null, projectId);
                var results = storage.query(queryVector, 5, filter).join();
                
                assertEquals(5, results.size(), "Should return 5 results");
                assertTrue(results.get(0).score() > 0.9, "Top result should be highly similar");
            } finally {
                storage.close();
            }
        }
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Deployment Integration Tests")
    class EdgeIntegrationTests {

        @Test
        @DisplayName("Full edge stack should work together")
        void testFullEdgeStack() throws Exception {
            Path dbPath = tempDir.resolve("full-edge.db");
            SQLiteConnectionManager connectionManager = SQLiteConnectionManager.forEdgeDeployment(dbPath.toString());
            
            // Run migrations
            SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
            migrator.migrateToLatest(connectionManager.createConnection());
            
            SQLiteVectorStorage vectorStorage = SQLiteVectorStorage.forEdgeDeployment(connectionManager, 384);
            SQLiteGraphStorage graphStorage = new SQLiteGraphStorage(connectionManager);
            
            try {
                vectorStorage.initialize().join();
                graphStorage.initialize().join();
                
                // Verify edge configurations
                assertEquals(-500, connectionManager.getCacheSize());
                assertEquals(0L, connectionManager.getMmapSize());
                assertEquals(100, vectorStorage.getBatchChunkSize());
                
                // Basic operations should work
                String projectId = UUID.randomUUID().toString();
                
                // Create project
                Connection conn = connectionManager.getWriteConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
                    stmt.setString(1, projectId);
                    stmt.setString(2, "Edge Project");
                    stmt.executeUpdate();
                } finally {
                    connectionManager.releaseWriteConnection(conn);
                }
                
                graphStorage.createProjectGraph(projectId).join();
                
                // Add entities
                var entity = br.edu.ifba.lightrag.core.Entity.builder()
                    .entityName("TestEntity")
                    .entityType("CONCEPT")
                    .description("Test description")
                    .addSourceChunkId("chunk1")
                    .build();
                graphStorage.upsertEntities(projectId, List.of(entity)).join();
                
                // Verify entity was stored (entity names are lowercased by storage)
                var entities = graphStorage.getAllEntities(projectId).join();
                assertEquals(1, entities.size());
                assertEquals("testentity", entities.get(0).getEntityName());
                
            } finally {
                graphStorage.close();
                vectorStorage.close();
                connectionManager.close();
            }
        }
    }
}
