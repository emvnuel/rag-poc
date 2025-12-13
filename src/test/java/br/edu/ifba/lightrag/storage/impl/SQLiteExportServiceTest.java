package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.CacheType;
import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;

/**
 * Unit tests for SQLiteExportService.
 * 
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Project export creates a standalone SQLite database file</li>
 *   <li>Exported file contains all project data (entities, relations, vectors, cache)</li>
 *   <li>Project import restores all data from exported file</li>
 *   <li>Import handles project ID remapping correctly</li>
 *   <li>Export/import maintains data integrity</li>
 * </ul>
 */
class SQLiteExportServiceTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteGraphStorage graphStorage;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteExtractionCacheStorage cacheStorage;
    private SQLiteKVStorage kvStorage;
    private SQLiteExportService exportService;
    
    private String projectId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("source.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations to create schema
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize storage implementations
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        cacheStorage = new SQLiteExtractionCacheStorage(connectionManager);
        cacheStorage.initialize().join();
        
        kvStorage = new SQLiteKVStorage(connectionManager);
        kvStorage.initialize().join();
        
        // Create export service
        exportService = new SQLiteExportService(connectionManager);
        
        // Set up test project and document
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        createProject(projectId);
        createDocument(documentId, projectId);
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeQuietly(graphStorage);
        closeQuietly(vectorStorage);
        closeQuietly(cacheStorage);
        closeQuietly(kvStorage);
        closeQuietly(connectionManager);
    }

    // ========================================================================
    // Export Tests
    // ========================================================================

    @Nested
    @DisplayName("Export Project Tests")
    class ExportProjectTests {

        @Test
        @DisplayName("exportProject should create a new SQLite database file")
        void testExportCreatesFile() throws Exception {
            Path exportPath = tempDir.resolve("export.db");
            
            exportService.exportProject(projectId, exportPath).join();
            
            assertTrue(Files.exists(exportPath), "Export file should exist");
            assertTrue(Files.size(exportPath) > 0, "Export file should not be empty");
        }

        @Test
        @DisplayName("exportProject should include all entities")
        void testExportIncludesEntities() throws Exception {
            // Add test entities
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("TestEntity1")
                .entityType("PERSON")
                .description("First test entity")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("TestEntity2")
                .entityType("ORGANIZATION")
                .description("Second test entity")
                .addSourceChunkId("chunk2")
                .build()).join();
            
            Path exportPath = tempDir.resolve("export-entities.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Verify exported data
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM graph_entities WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Should export 2 entities");
                } finally {
                    exportConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("exportProject should include all relations")
        void testExportIncludesRelations() throws Exception {
            // Add entities and relation
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("SourceEntity")
                .entityType("PERSON")
                .description("Source")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("TargetEntity")
                .entityType("ORGANIZATION")
                .description("Target")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            graphStorage.upsertRelation(projectId, Relation.builder()
                .srcId("SourceEntity")
                .tgtId("TargetEntity")
                .description("works for")
                .keywords("employment")
                .weight(1.0)
                .addSourceChunkId("chunk1")
                .build()).join();
            
            Path exportPath = tempDir.resolve("export-relations.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Verify exported data
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM graph_relations WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Should export 1 relation");
                } finally {
                    exportConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("exportProject should include all vectors")
        void testExportIncludesVectors() throws Exception {
            // Add test vectors
            String chunkId1 = UUID.randomUUID().toString();
            String chunkId2 = UUID.randomUUID().toString();
            
            vectorStorage.upsert(chunkId1, createTestVector(384, 0.1f),
                new VectorMetadata("chunk", "Content 1", documentId, 0, projectId)).join();
            vectorStorage.upsert(chunkId2, createTestVector(384, 0.2f),
                new VectorMetadata("chunk", "Content 2", documentId, 1, projectId)).join();
            
            Path exportPath = tempDir.resolve("export-vectors.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Verify exported data
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM vectors WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Should export 2 vectors");
                } finally {
                    exportConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("exportProject should include extraction cache")
        void testExportIncludesCache() throws Exception {
            // Add cache entry
            cacheStorage.store(
                projectId,
                CacheType.KEYWORD_EXTRACTION,
                UUID.randomUUID().toString(),
                "hash123",
                "{\"keywords\": [\"test\"]}",
                100
            ).join();
            
            Path exportPath = tempDir.resolve("export-cache.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Verify exported data
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM extraction_cache WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Should export 1 cache entry");
                } finally {
                    exportConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("exportProject should only include data for specified project")
        void testExportIsolatesProject() throws Exception {
            // Create another project with data
            String otherProjectId = UUID.randomUUID().toString();
            createProject(otherProjectId);
            graphStorage.createProjectGraph(otherProjectId).join();
            
            graphStorage.upsertEntity(otherProjectId, Entity.builder()
                .entityName("OtherEntity")
                .entityType("PERSON")
                .description("Other project entity")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            // Add entity to target project
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("TargetEntity")
                .entityType("PERSON")
                .description("Target project entity")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            Path exportPath = tempDir.resolve("export-isolated.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Verify only target project data is exported
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM graph_entities")) {
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Should only export entities from target project");
                } finally {
                    exportConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("exportProject should throw if project does not exist")
        void testExportNonExistentProject() {
            String nonExistentId = UUID.randomUUID().toString();
            Path exportPath = tempDir.resolve("export-nonexistent.db");
            
            // CompletableFuture wraps exceptions in CompletionException
            var exception = assertThrows(java.util.concurrent.CompletionException.class, () -> 
                exportService.exportProject(nonExistentId, exportPath).join());
            assertTrue(exception.getCause() instanceof IllegalArgumentException,
                "Cause should be IllegalArgumentException");
        }
    }

    // ========================================================================
    // Import Tests
    // ========================================================================

    @Nested
    @DisplayName("Import Project Tests")
    class ImportProjectTests {

        @Test
        @DisplayName("importProject should restore all entities")
        void testImportRestoresEntities() throws Exception {
            // Add test data and export
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("ImportTestEntity")
                .entityType("CONCEPT")
                .description("Entity for import test")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            Path exportPath = tempDir.resolve("import-test.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Create new target database
            Path targetDbPath = tempDir.resolve("target.db");
            try (SQLiteConnectionManager targetConn = new SQLiteConnectionManager(targetDbPath.toString())) {
                SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
                migrator.migrateToLatest(targetConn.createConnection());
                
                SQLiteExportService targetExportService = new SQLiteExportService(targetConn);
                String newProjectId = UUID.randomUUID().toString();
                
                // Create target project
                createProjectInDb(targetConn, newProjectId);
                
                // Import
                targetExportService.importProject(exportPath, newProjectId).join();
                
                // Verify imported data
                SQLiteGraphStorage targetGraphStorage = new SQLiteGraphStorage(targetConn);
                targetGraphStorage.initialize().join();
                targetGraphStorage.createProjectGraph(newProjectId).join();
                
                Entity imported = targetGraphStorage.getEntity(newProjectId, "importtestentity").join();
                assertNotNull(imported, "Entity should be imported");
                assertEquals("importtestentity", imported.getEntityName());
                assertEquals("CONCEPT", imported.getEntityType());
                
                targetGraphStorage.close();
            }
        }

        @Test
        @DisplayName("importProject should restore all vectors")
        void testImportRestoresVectors() throws Exception {
            // Add test vector and export
            String chunkId = UUID.randomUUID().toString();
            vectorStorage.upsert(chunkId, createTestVector(384, 0.5f),
                new VectorMetadata("chunk", "Import test content", documentId, 0, projectId)).join();
            
            Path exportPath = tempDir.resolve("import-vectors.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Create new target database
            Path targetDbPath = tempDir.resolve("target-vectors.db");
            try (SQLiteConnectionManager targetConn = new SQLiteConnectionManager(targetDbPath.toString())) {
                SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
                migrator.migrateToLatest(targetConn.createConnection());
                
                SQLiteExportService targetExportService = new SQLiteExportService(targetConn);
                String newProjectId = UUID.randomUUID().toString();
                String newDocId = UUID.randomUUID().toString();
                
                // Create target project and document
                createProjectInDb(targetConn, newProjectId);
                createDocumentInDb(targetConn, newDocId, newProjectId);
                
                // Import
                targetExportService.importProject(exportPath, newProjectId).join();
                
                // Verify imported vector count
                Connection conn = targetConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM vectors WHERE project_id = ?")) {
                    stmt.setString(1, newProjectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Should import 1 vector");
                } finally {
                    targetConn.releaseReadConnection(conn);
                }
            }
        }

        @Test
        @DisplayName("importProject should remap project IDs")
        void testImportRemapsProjectId() throws Exception {
            // Add test data and export
            graphStorage.upsertEntity(projectId, Entity.builder()
                .entityName("RemapTest")
                .entityType("CONCEPT")
                .description("Test remapping")
                .addSourceChunkId("chunk1")
                .build()).join();
            
            Path exportPath = tempDir.resolve("remap-test.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Import with new project ID
            String newProjectId = UUID.randomUUID().toString();
            createProject(newProjectId);
            graphStorage.createProjectGraph(newProjectId).join();
            
            exportService.importProject(exportPath, newProjectId).join();
            
            // Verify entity is under new project ID
            Entity imported = graphStorage.getEntity(newProjectId, "remaptest").join();
            assertNotNull(imported, "Entity should exist under new project ID");
            
            // Original project should still have its entity
            Entity original = graphStorage.getEntity(projectId, "remaptest").join();
            assertNotNull(original, "Original entity should still exist");
        }

        @Test
        @DisplayName("importProject should throw if export file does not exist")
        void testImportNonExistentFile() {
            Path nonExistentPath = tempDir.resolve("nonexistent.db");
            String newProjectId = UUID.randomUUID().toString();
            
            // CompletableFuture wraps exceptions in CompletionException
            var exception = assertThrows(java.util.concurrent.CompletionException.class, () -> 
                exportService.importProject(nonExistentPath, newProjectId).join());
            assertTrue(exception.getCause() instanceof IllegalArgumentException,
                "Cause should be IllegalArgumentException");
        }
    }

    // ========================================================================
    // Round-Trip Tests
    // ========================================================================

    @Nested
    @DisplayName("Round-Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Export and import should preserve data integrity")
        void testRoundTripPreservesData() throws Exception {
            // Create comprehensive test data
            graphStorage.upsertEntities(projectId, List.of(
                Entity.builder()
                    .entityName("Alice")
                    .entityType("PERSON")
                    .description("A software engineer")
                    .addSourceChunkId("chunk1")
                    .build(),
                Entity.builder()
                    .entityName("TechCorp")
                    .entityType("ORGANIZATION")
                    .description("A technology company")
                    .addSourceChunkId("chunk1")
                    .build()
            )).join();
            
            graphStorage.upsertRelation(projectId, Relation.builder()
                .srcId("Alice")
                .tgtId("TechCorp")
                .description("works at")
                .keywords("employment,job")
                .weight(1.0)
                .addSourceChunkId("chunk1")
                .build()).join();
            
            String chunkId = UUID.randomUUID().toString();
            vectorStorage.upsert(chunkId, createTestVector(384, 0.5f),
                new VectorMetadata("chunk", "Alice works at TechCorp", documentId, 0, projectId)).join();
            
            // Export
            Path exportPath = tempDir.resolve("roundtrip.db");
            exportService.exportProject(projectId, exportPath).join();
            
            // Import to new location
            Path targetDbPath = tempDir.resolve("roundtrip-target.db");
            try (SQLiteConnectionManager targetConn = new SQLiteConnectionManager(targetDbPath.toString())) {
                SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
                migrator.migrateToLatest(targetConn.createConnection());
                
                SQLiteExportService targetExportService = new SQLiteExportService(targetConn);
                String newProjectId = UUID.randomUUID().toString();
                String newDocId = UUID.randomUUID().toString();
                
                createProjectInDb(targetConn, newProjectId);
                createDocumentInDb(targetConn, newDocId, newProjectId);
                
                targetExportService.importProject(exportPath, newProjectId).join();
                
                // Verify all data
                SQLiteGraphStorage targetGraph = new SQLiteGraphStorage(targetConn);
                targetGraph.initialize().join();
                targetGraph.createProjectGraph(newProjectId).join();
                
                // Check entities
                List<Entity> entities = targetGraph.getAllEntities(newProjectId).join();
                assertEquals(2, entities.size(), "Should have 2 entities");
                
                // Check relations
                List<Relation> relations = targetGraph.getAllRelations(newProjectId).join();
                assertEquals(1, relations.size(), "Should have 1 relation");
                assertEquals("works at", relations.get(0).getDescription());
                
                // Check vectors
                Connection conn = targetConn.getReadConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT content FROM vectors WHERE project_id = ?")) {
                    stmt.setString(1, newProjectId);
                    ResultSet rs = stmt.executeQuery();
                    assertTrue(rs.next());
                    assertEquals("Alice works at TechCorp", rs.getString("content"));
                } finally {
                    targetConn.releaseReadConnection(conn);
                }
                
                targetGraph.close();
            }
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void createProject(String projId) throws Exception {
        createProjectInDb(connectionManager, projId);
    }

    private void createDocument(String docId, String projId) throws Exception {
        createDocumentInDb(connectionManager, docId, projId);
    }

    private void createProjectInDb(SQLiteConnectionManager connMgr, String projId) throws Exception {
        Connection conn = connMgr.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, "Test Project");
            stmt.executeUpdate();
        } finally {
            connMgr.releaseWriteConnection(conn);
        }
    }

    private void createDocumentInDb(SQLiteConnectionManager connMgr, String docId, String projId) throws Exception {
        Connection conn = connMgr.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO documents (id, project_id, type, status, created_at, updated_at) " +
                "VALUES (?, ?, 'TXT', 'NOT_PROCESSED', datetime('now'), datetime('now'))")) {
            stmt.setString(1, docId);
            stmt.setString(2, projId);
            stmt.executeUpdate();
        } finally {
            connMgr.releaseWriteConnection(conn);
        }
    }

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

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
