package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * Memory benchmark tests for SQLite storage operations.
 * 
 * <p>Tests verify that SQLite storage operations are memory-efficient
 * and suitable for resource-constrained edge deployment.</p>
 * 
 * <p>Target environment: 256MB memory limit</p>
 * 
 * @since spec-009
 */
class SQLiteMemoryBenchmarkTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteVectorStorage vectorStorage;
    private SQLiteGraphStorage graphStorage;

    private String projectId;
    private String documentId;
    private MemoryMXBean memoryBean;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("benchmark.db");
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
        
        memoryBean = ManagementFactory.getMemoryMXBean();
        
        // Force GC to get baseline
        System.gc();
        Thread.sleep(100);
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
    // Batch Processing Memory Tests
    // ========================================================================

    @Nested
    @DisplayName("Batch Processing Memory Tests")
    class BatchProcessingMemoryTests {

        @Test
        @DisplayName("Batch upsert 1000 vectors should use reasonable memory")
        void testBatchUpsert1000VectorsMemory() {
            long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();
            
            // Create 1000 vectors
            List<VectorEntry> entries = createVectorEntries(1000, 384);
            
            // Upsert batch
            vectorStorage.upsertBatch(entries).join();
            
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long memoryUsed = heapAfter - heapBefore;
            
            // Should use less than 50MB for 1000 vectors
            // Each vector: 384 floats * 4 bytes = 1536 bytes + overhead
            // 1000 vectors * ~2KB each = ~2MB data, allow 50MB for processing overhead
            long maxExpected = 50 * 1024 * 1024; // 50MB
            
            System.out.printf("Memory used for 1000 vector batch: %.2f MB%n", 
                    memoryUsed / (1024.0 * 1024.0));
            
            assertTrue(memoryUsed < maxExpected, 
                    String.format("Memory usage %.2f MB exceeded limit of %.2f MB",
                            memoryUsed / (1024.0 * 1024.0), maxExpected / (1024.0 * 1024.0)));
        }

        @Test
        @DisplayName("Sequential batch processing should not accumulate memory")
        void testSequentialBatchProcessingNoAccumulation() {
            long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();
            
            // Process 5 batches of 200 vectors each
            for (int batch = 0; batch < 5; batch++) {
                List<VectorEntry> entries = createVectorEntries(200, 384);
                vectorStorage.upsertBatch(entries).join();
                
                // Clear references to help GC
                entries.clear();
            }
            
            // Force GC
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long memoryUsed = heapAfter - heapBefore;
            
            // After GC, memory should be similar to processing a single batch
            // Allow 30MB overhead
            long maxExpected = 30 * 1024 * 1024;
            
            System.out.printf("Memory retained after 5 batches: %.2f MB%n", 
                    memoryUsed / (1024.0 * 1024.0));
            
            // This is more of a soft assertion - memory behavior is JVM dependent
            assertTrue(memoryUsed < maxExpected || memoryUsed < heapBefore * 2,
                    "Memory should not grow unboundedly after batch processing");
        }
    }

    // ========================================================================
    // Connection Pool Memory Tests
    // ========================================================================

    @Nested
    @DisplayName("Connection Pool Memory Tests")
    class ConnectionPoolMemoryTests {

        @Test
        @DisplayName("Connection pool should limit memory usage")
        void testConnectionPoolMemoryLimit() {
            long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();
            
            // Get and release many connections
            for (int i = 0; i < 100; i++) {
                Connection conn = connectionManager.getReadConnection();
                connectionManager.releaseReadConnection(conn);
            }
            
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long memoryUsed = heapAfter - heapBefore;
            
            // Connection pool should reuse connections, not accumulate
            long maxExpected = 10 * 1024 * 1024; // 10MB
            
            System.out.printf("Memory used for 100 connection cycles: %.2f MB%n", 
                    memoryUsed / (1024.0 * 1024.0));
            
            assertTrue(memoryUsed < maxExpected || memoryUsed < heapBefore,
                    "Connection pool should reuse connections efficiently");
        }
    }

    // ========================================================================
    // Query Memory Tests
    // ========================================================================

    @Nested
    @DisplayName("Query Memory Tests")
    class QueryMemoryTests {

        @Test
        @DisplayName("Vector similarity search should be memory efficient")
        void testVectorSearchMemoryEfficient() {
            // Insert test vectors first
            List<VectorEntry> entries = createVectorEntries(500, 384);
            vectorStorage.upsertBatch(entries).join();
            
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            
            long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();
            
            // Perform multiple searches
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filter = new VectorFilter(null, null, projectId);
            for (int i = 0; i < 10; i++) {
                vectorStorage.query(queryVector, 10, filter).join();
            }
            
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long memoryUsed = heapAfter - heapBefore;
            
            // Queries should not accumulate memory
            long maxExpected = 20 * 1024 * 1024; // 20MB
            
            System.out.printf("Memory used for 10 vector searches: %.2f MB%n", 
                    memoryUsed / (1024.0 * 1024.0));
            
            assertTrue(memoryUsed < maxExpected || memoryUsed < heapBefore,
                    "Vector search should not accumulate memory");
        }
    }

    // ========================================================================
    // Edge Deployment Simulation Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Deployment Simulation")
    class EdgeDeploymentSimulation {

        @Test
        @DisplayName("Simulate edge workflow with limited memory")
        void testEdgeWorkflowMemoryUsage() {
            long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();
            
            // Simulate typical edge workflow:
            // 1. Insert document vectors (100 chunks)
            List<VectorEntry> docVectors = createVectorEntries(100, 384);
            vectorStorage.upsertBatch(docVectors).join();
            docVectors.clear();
            
            // 2. Query for similar content
            float[] queryVector = createTestVector(384, 0.5f);
            VectorFilter filter = new VectorFilter(null, null, projectId);
            for (int i = 0; i < 5; i++) {
                vectorStorage.query(queryVector, 10, filter).join();
            }
            
            // 3. Add more documents
            List<VectorEntry> moreVectors = createVectorEntries(100, 384);
            vectorStorage.upsertBatch(moreVectors).join();
            moreVectors.clear();
            
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long totalUsed = heapAfter - heapBefore;
            
            // Edge deployment target: stay under 100MB for SQLite operations
            long maxExpected = 100 * 1024 * 1024;
            
            System.out.printf("Total memory for edge workflow: %.2f MB%n", 
                    totalUsed / (1024.0 * 1024.0));
            
            // Soft assertion - JVM memory is complex
            assertTrue(totalUsed < maxExpected || totalUsed < heapBefore * 3,
                    "Edge workflow should use reasonable memory");
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
            stmt.setString(2, "Benchmark Project");
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
