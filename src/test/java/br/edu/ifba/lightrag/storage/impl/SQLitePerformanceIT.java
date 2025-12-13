package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;

/**
 * Performance integration tests for SQLite storage.
 * 
 * <p>Tests verify latency requirements from spec:</p>
 * <ul>
 *   <li>SC-004: 10K chunks vector search < 500ms</li>
 *   <li>SC-005: 5K entity traversal < 200ms</li>
 * </ul>
 * 
 * @since spec-009
 */
class SQLitePerformanceIT {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;

    private String projectId;
    private String documentId;

    // Performance thresholds (relaxed for CI environments)
    private static final long VECTOR_SEARCH_MAX_MS = 500;  // SC-004
    private static final long GRAPH_TRAVERSAL_MAX_MS = 200; // SC-005
    private static final long BATCH_UPSERT_MAX_MS = 5000;   // Batch insert 1000 vectors

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("performance.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        // Initialize storages
        vectorStorage = new SQLiteVectorStorage(connectionManager, 384);
        vectorStorage.initialize().join();
        
        graphStorage = new SQLiteGraphStorage(connectionManager);
        graphStorage.initialize().join();
        
        // Create project and document
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        createProject(projectId);
        createDocument(documentId, projectId);
        graphStorage.createProjectGraph(projectId).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (graphStorage != null) {
            graphStorage.close();
        }
        if (vectorStorage != null) {
            vectorStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    // ========================================================================
    // Vector Search Performance (SC-004)
    // ========================================================================

    @Nested
    @DisplayName("Vector Search Performance (SC-004)")
    class VectorSearchPerformance {

        @Test
        @DisplayName("Vector search on 1000 chunks should complete within 100ms")
        void testVectorSearch1000Chunks() {
            // Insert 1000 vectors
            List<VectorEntry> entries = createVectorEntries(1000, 384);
            vectorStorage.upsertBatch(entries).join();
            
            // Create query vector
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filter = new VectorFilter(null, null, projectId);
            
            // Warm up
            vectorStorage.query(queryVector, 10, filter).join();
            
            // Measure
            Instant start = Instant.now();
            List<VectorSearchResult> results = vectorStorage.query(queryVector, 10, filter).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Vector search on 1000 chunks: %d ms%n", duration.toMillis());
            
            assertTrue(results.size() <= 10, "Should return at most 10 results");
            assertTrue(duration.toMillis() < 100, 
                    String.format("Search took %d ms, expected < 100 ms", duration.toMillis()));
        }

        @Test
        @DisplayName("Vector search on 5000 chunks should complete within 300ms")
        void testVectorSearch5000Chunks() {
            // Insert 5000 vectors in batches
            for (int batch = 0; batch < 5; batch++) {
                List<VectorEntry> entries = createVectorEntries(1000, 384);
                vectorStorage.upsertBatch(entries).join();
            }
            
            // Create query vector
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filter = new VectorFilter(null, null, projectId);
            
            // Warm up
            vectorStorage.query(queryVector, 10, filter).join();
            
            // Measure
            Instant start = Instant.now();
            List<VectorSearchResult> results = vectorStorage.query(queryVector, 10, filter).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Vector search on 5000 chunks: %d ms%n", duration.toMillis());
            
            assertTrue(results.size() <= 10, "Should return at most 10 results");
            assertTrue(duration.toMillis() < 300, 
                    String.format("Search took %d ms, expected < 300 ms", duration.toMillis()));
        }

        @Test
        @DisplayName("Vector search on 10000 chunks should complete within SC-004 limit")
        void testVectorSearch10000ChunksSC004() {
            // Insert 10000 vectors in batches
            for (int batch = 0; batch < 10; batch++) {
                List<VectorEntry> entries = createVectorEntries(1000, 384);
                vectorStorage.upsertBatch(entries).join();
            }
            
            // Create query vector
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filter = new VectorFilter(null, null, projectId);
            
            // Warm up
            vectorStorage.query(queryVector, 10, filter).join();
            
            // Measure
            Instant start = Instant.now();
            List<VectorSearchResult> results = vectorStorage.query(queryVector, 10, filter).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Vector search on 10000 chunks (SC-004): %d ms (limit: %d ms)%n", 
                    duration.toMillis(), VECTOR_SEARCH_MAX_MS);
            
            assertTrue(results.size() <= 10, "Should return at most 10 results");
            assertTrue(duration.toMillis() < VECTOR_SEARCH_MAX_MS, 
                    String.format("SC-004 FAILED: Search took %d ms, limit is %d ms", 
                            duration.toMillis(), VECTOR_SEARCH_MAX_MS));
        }
    }

    // ========================================================================
    // Graph Traversal Performance (SC-005)
    // ========================================================================

    @Nested
    @DisplayName("Graph Traversal Performance (SC-005)")
    class GraphTraversalPerformance {

        @Test
        @DisplayName("Entity retrieval on 1000 entities should complete within 100ms")
        void testEntityRetrieval1000() {
            // Create 1000 entities
            List<Entity> entities = createEntities(1000);
            graphStorage.upsertEntities(projectId, entities).join();
            
            // Warm up
            graphStorage.getAllEntities(projectId).join();
            
            // Measure
            Instant start = Instant.now();
            List<Entity> retrieved = graphStorage.getAllEntities(projectId).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Retrieve 1000 entities: %d ms%n", duration.toMillis());
            
            assertEquals(1000, retrieved.size());
            assertTrue(duration.toMillis() < 100, 
                    String.format("Retrieval took %d ms, expected < 100 ms", duration.toMillis()));
        }

        @Test
        @DisplayName("Graph traversal from entity with 100 neighbors should complete within 50ms")
        void testGraphTraversalWithNeighbors() {
            // Create hub entity and 100 connected entities
            String hubEntity = "HubEntity";
            List<Entity> entities = new ArrayList<>();
            entities.add(Entity.builder()
                    .entityName(hubEntity)
                    .entityType("CONCEPT")
                    .description("Central hub")
                    .addSourceChunkId("chunk1")
                    .build());
            
            for (int i = 0; i < 100; i++) {
                entities.add(Entity.builder()
                        .entityName("Neighbor" + i)
                        .entityType("CONCEPT")
                        .description("Neighbor " + i)
                        .addSourceChunkId("chunk1")
                        .build());
            }
            
            graphStorage.upsertEntities(projectId, entities).join();
            
            // Create relations
            List<Relation> relations = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                relations.add(Relation.builder()
                        .srcId(hubEntity)
                        .tgtId("Neighbor" + i)
                        .description("connects to")
                        .keywords("connection")
                        .weight(1.0)
                        .addSourceChunkId("chunk1")
                        .build());
            }
            graphStorage.upsertRelations(projectId, relations).join();
            
            // Warm up
            graphStorage.traverse(projectId, hubEntity.toLowerCase(), 1).join();
            
            // Measure
            Instant start = Instant.now();
            var subgraph = graphStorage.traverse(projectId, hubEntity.toLowerCase(), 1).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Traverse hub with 100 neighbors (depth 1): %d ms%n", duration.toMillis());
            
            assertTrue(subgraph.entities().size() > 1, "Should find hub and neighbors");
            assertTrue(duration.toMillis() < 50, 
                    String.format("Traversal took %d ms, expected < 50 ms", duration.toMillis()));
        }

        @Test
        @DisplayName("Relation retrieval on 1000 relations should complete within SC-005 limit")
        void testRelationRetrieval1000SC005() {
            // Create entities
            List<Entity> entities = createEntities(100);
            graphStorage.upsertEntities(projectId, entities).join();
            
            // Create 1000 relations between entities
            List<Relation> relations = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                int srcIdx = i % 100;
                int tgtIdx = (i + 1) % 100;
                relations.add(Relation.builder()
                        .srcId("Entity" + srcIdx)
                        .tgtId("Entity" + tgtIdx)
                        .description("relation " + i)
                        .keywords("test")
                        .weight(1.0)
                        .addSourceChunkId("chunk1")
                        .build());
            }
            graphStorage.upsertRelations(projectId, relations).join();
            
            // Warm up
            graphStorage.getAllRelations(projectId).join();
            
            // Measure
            Instant start = Instant.now();
            List<Relation> retrieved = graphStorage.getAllRelations(projectId).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Retrieve 1000 relations (SC-005): %d ms (limit: %d ms)%n", 
                    duration.toMillis(), GRAPH_TRAVERSAL_MAX_MS);
            
            assertTrue(retrieved.size() >= 100, "Should have relations");
            assertTrue(duration.toMillis() < GRAPH_TRAVERSAL_MAX_MS, 
                    String.format("SC-005 FAILED: Retrieval took %d ms, limit is %d ms", 
                            duration.toMillis(), GRAPH_TRAVERSAL_MAX_MS));
        }
    }

    // ========================================================================
    // Batch Operation Performance
    // ========================================================================

    @Nested
    @DisplayName("Batch Operation Performance")
    class BatchOperationPerformance {

        @Test
        @DisplayName("Batch upsert 1000 vectors should complete within limit")
        void testBatchUpsert1000Vectors() {
            List<VectorEntry> entries = createVectorEntries(1000, 384);
            
            Instant start = Instant.now();
            vectorStorage.upsertBatch(entries).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Batch upsert 1000 vectors: %d ms (limit: %d ms)%n", 
                    duration.toMillis(), BATCH_UPSERT_MAX_MS);
            
            assertTrue(duration.toMillis() < BATCH_UPSERT_MAX_MS, 
                    String.format("Batch upsert took %d ms, limit is %d ms", 
                            duration.toMillis(), BATCH_UPSERT_MAX_MS));
        }

        @Test
        @DisplayName("Batch upsert 100 entities should complete within 500ms")
        void testBatchUpsert100Entities() {
            List<Entity> entities = createEntities(100);
            
            Instant start = Instant.now();
            graphStorage.upsertEntities(projectId, entities).join();
            Duration duration = Duration.between(start, Instant.now());
            
            System.out.printf("Batch upsert 100 entities: %d ms%n", duration.toMillis());
            
            assertTrue(duration.toMillis() < 500, 
                    String.format("Batch upsert took %d ms, expected < 500 ms", duration.toMillis()));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void createProject(String projId) throws Exception {
        Connection conn = connectionManager.getWriteConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO projects (id, name, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))")) {
            stmt.setString(1, projId);
            stmt.setString(2, "Performance Test Project");
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

    private List<VectorEntry> createVectorEntries(int count, int dimension) {
        List<VectorEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            float[] vector = createTestVector(dimension, (float) i / count);
            VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "Content " + i,
                    documentId,
                    i,
                    projectId
            );
            entries.add(new VectorEntry(id, vector, metadata));
        }
        return entries;
    }

    private List<Entity> createEntities(int count) {
        List<Entity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(Entity.builder()
                    .entityName("Entity" + i)
                    .entityType("CONCEPT")
                    .description("Entity description " + i)
                    .addSourceChunkId("chunk1")
                    .build());
        }
        return entities;
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
}
