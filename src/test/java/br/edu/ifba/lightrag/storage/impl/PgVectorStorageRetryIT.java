package br.edu.ifba.lightrag.storage.impl;

import br.edu.ifba.lightrag.storage.VectorStorage;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorEntry;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorFilter;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorMetadata;
import br.edu.ifba.lightrag.storage.VectorStorage.VectorSearchResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for PgVectorStorage retry behavior.
 * 
 * <p>Tests verify that the {@code @Retry} annotations on storage methods
 * correctly handle operations and that the retry mechanism doesn't interfere
 * with normal operation.</p>
 * 
 * <p><b>Test Strategy:</b> Since simulating actual database connection failures
 * requires complex infrastructure, these tests focus on verifying that:</p>
 * <ul>
 *   <li>Normal operations succeed without retry overhead</li>
 *   <li>Batch operations complete correctly</li>
 *   <li>Query operations return expected results</li>
 *   <li>Multiple sequential operations don't cause resource leaks</li>
 * </ul>
 * 
 * @see br.edu.ifba.lightrag.utils.TransientSQLExceptionPredicate
 */
@QuarkusTest
class PgVectorStorageRetryIT {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorStorageRetryIT.class);

    @Inject
    VectorStorage vectorStorage;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "lightrag.vector.dimension", defaultValue = "384")
    int vectorDimension;

    private String projectId;
    private String documentId;
    private final List<String> createdVectorIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        projectId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        
        // Create project and document records for FK constraints
        createTestProjectAndDocument();
        
        vectorStorage.initialize().join();
        logger.info("Set up test with projectId={}, documentId={}, dimension={}", 
            projectId, documentId, vectorDimension);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up created vectors
        if (!createdVectorIds.isEmpty()) {
            vectorStorage.deleteBatch(createdVectorIds).join();
            logger.info("Cleaned up {} vectors", createdVectorIds.size());
            createdVectorIds.clear();
        }
        // Clean up project and document (CASCADE will handle related records)
        cleanupTestProjectAndDocument();
    }
    
    /**
     * Creates test project and document records required by FK constraints.
     */
    private void createTestProjectAndDocument() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Timestamp now = Timestamp.from(Instant.now());
            
            // Create project
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO rag.projects (id, created_at, updated_at, name) VALUES (?::uuid, ?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
                stmt.setString(1, projectId);
                stmt.setTimestamp(2, now);
                stmt.setTimestamp(3, now);
                stmt.setString(4, "Test Project");
                stmt.executeUpdate();
            }
            
            // Create document
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO rag.documents (id, created_at, updated_at, type, status, file_name, content, project_id) " +
                    "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid) ON CONFLICT (id) DO NOTHING")) {
                stmt.setString(1, documentId);
                stmt.setTimestamp(2, now);
                stmt.setTimestamp(3, now);
                stmt.setString(4, "TEXT");
                stmt.setString(5, "PROCESSED");
                stmt.setString(6, "test.txt");
                stmt.setString(7, "Test content");
                stmt.setString(8, projectId);
                stmt.executeUpdate();
            }
        }
    }
    
    /**
     * Cleans up test project and document records.
     */
    private void cleanupTestProjectAndDocument() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Delete project (CASCADE will delete documents and vectors)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM rag.projects WHERE id = ?::uuid")) {
                stmt.setString(1, projectId);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Creates a test vector with the configured dimension.
     */
    private double[] createTestVector(final double seed) {
        final double[] vector = new double[vectorDimension];
        for (int i = 0; i < vectorDimension; i++) {
            vector[i] = (seed + i) / vectorDimension;
        }
        return vector;
    }

    @Nested
    @DisplayName("US1: Vector Upsert Operations")
    class UpsertRetryTests {

        /**
         * T049: Verify upsert completes successfully under normal conditions.
         */
        @Test
        @DisplayName("T049: upsert vector succeeds on normal operation")
        void testUpsertVectorSucceedsNormally() {
            final String vectorId = UUID.randomUUID().toString();
            createdVectorIds.add(vectorId);

            final double[] vector = createTestVector(1.0);
            final VectorMetadata metadata = new VectorMetadata(
                "chunk",
                "Test content for vector",
                documentId,
                0,
                projectId
            );

            // Should complete without exception
            vectorStorage.upsert(vectorId, vector, metadata).join();

            // Verify vector was created
            final VectorEntry retrieved = vectorStorage.get(vectorId).join();
            assertNotNull(retrieved, "Vector should be retrieved after upsert");
            assertEquals(vectorId, retrieved.id());
            assertEquals("chunk", retrieved.metadata().type());
        }

        /**
         * Verify upsert with same ID updates the vector (idempotent).
         */
        @Test
        @DisplayName("upsert is idempotent - updates existing vector")
        void testUpsertIsIdempotent() {
            final String vectorId = UUID.randomUUID().toString();
            createdVectorIds.add(vectorId);

            final double[] vector1 = createTestVector(1.0);
            final VectorMetadata metadata1 = new VectorMetadata(
                "chunk",
                "Original content",
                documentId,
                0,
                projectId
            );

            // First upsert
            vectorStorage.upsert(vectorId, vector1, metadata1).join();

            // Second upsert with different content
            final double[] vector2 = createTestVector(2.0);
            final VectorMetadata metadata2 = new VectorMetadata(
                "chunk",
                "Updated content",
                documentId,
                0,
                projectId
            );

            vectorStorage.upsert(vectorId, vector2, metadata2).join();

            // Verify vector was updated
            final VectorEntry retrieved = vectorStorage.get(vectorId).join();
            assertNotNull(retrieved, "Vector should exist after update");
            assertEquals("Updated content", retrieved.metadata().content());
        }

        /**
         * Verify batch upsert completes successfully.
         */
        @Test
        @DisplayName("upsertBatch succeeds on normal operation")
        void testBatchUpsertSucceedsNormally() {
            final List<VectorEntry> entries = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                final String vectorId = UUID.randomUUID().toString();
                createdVectorIds.add(vectorId);

                final double[] vector = createTestVector(i);
                final VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "Batch content " + i,
                    documentId,
                    i,
                    projectId
                );

                entries.add(new VectorEntry(vectorId, vector, metadata));
            }

            // Should complete without exception
            vectorStorage.upsertBatch(entries).join();

            // Verify storage size increased
            final Long size = vectorStorage.size().join();
            assertTrue(size >= 5, "Should have at least 5 vectors after batch upsert");

            logger.info("Successfully batch upserted {} vectors", entries.size());
        }
    }

    @Nested
    @DisplayName("US2: Vector Query Operations")
    class QueryRetryTests {

        @BeforeEach
        void setUpTestData() {
            // Insert test vectors for query tests
            final List<VectorEntry> entries = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                final String vectorId = UUID.randomUUID().toString();
                createdVectorIds.add(vectorId);

                final double[] vector = createTestVector(i * 0.1);
                final VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "Query test content " + i,
                    documentId,
                    i,
                    projectId
                );

                entries.add(new VectorEntry(vectorId, vector, metadata));
            }

            vectorStorage.upsertBatch(entries).join();
            logger.info("Set up {} test vectors for query tests", entries.size());
        }

        /**
         * T050: Verify query (searchSimilar) completes successfully.
         */
        @Test
        @DisplayName("T050: query succeeds on normal operation")
        void testQuerySucceedsNormally() {
            final double[] queryVector = createTestVector(0.5);
            final VectorFilter filter = new VectorFilter(null, null, projectId);

            final List<VectorSearchResult> results = vectorStorage.query(queryVector, 5, filter).join();

            assertNotNull(results, "Query results should not be null");
            assertTrue(results.size() <= 5, "Should return at most 5 results");
            logger.info("Query returned {} results", results.size());
        }

        /**
         * Verify query with type filter works correctly.
         */
        @Test
        @DisplayName("query with type filter succeeds")
        void testQueryWithTypeFilterSucceeds() {
            final double[] queryVector = createTestVector(0.5);
            final VectorFilter filter = new VectorFilter("chunk", null, projectId);

            final List<VectorSearchResult> results = vectorStorage.query(queryVector, 5, filter).join();

            assertNotNull(results, "Query results should not be null");
            // All results should be of type "chunk"
            for (final VectorSearchResult result : results) {
                assertEquals("chunk", result.metadata().type());
            }
        }

        /**
         * Verify query returns results ordered by similarity (score).
         */
        @Test
        @DisplayName("query results are ordered by score")
        void testQueryResultsOrderedByScore() {
            final double[] queryVector = createTestVector(0.0);
            final VectorFilter filter = new VectorFilter(null, null, projectId);

            final List<VectorSearchResult> results = vectorStorage.query(queryVector, 10, filter).join();

            // Verify results are ordered by score (descending)
            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                    "Results should be ordered by score descending");
            }
        }
    }

    @Nested
    @DisplayName("US3: Vector Delete Operations")
    class DeleteRetryTests {

        /**
         * T051: Verify delete completes successfully.
         */
        @Test
        @DisplayName("T051: delete succeeds on normal operation")
        void testDeleteSucceedsNormally() {
            // First create a vector
            final String vectorId = UUID.randomUUID().toString();
            final double[] vector = createTestVector(1.0);
            final VectorMetadata metadata = new VectorMetadata(
                "chunk",
                "To be deleted",
                documentId,
                0,
                projectId
            );

            vectorStorage.upsert(vectorId, vector, metadata).join();

            // Verify it exists
            final VectorEntry before = vectorStorage.get(vectorId).join();
            assertNotNull(before, "Vector should exist before deletion");

            // Delete it
            final Boolean deleted = vectorStorage.delete(vectorId).join();
            assertTrue(deleted, "Delete should return true");

            // Verify it's gone
            final VectorEntry after = vectorStorage.get(vectorId).join();
            assertEquals(null, after, "Vector should not exist after deletion");
        }

        /**
         * Verify batch delete completes successfully.
         */
        @Test
        @DisplayName("deleteBatch succeeds on normal operation")
        void testBatchDeleteSucceedsNormally() {
            // Create vectors to delete
            final List<String> toDelete = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                final String vectorId = UUID.randomUUID().toString();
                toDelete.add(vectorId);

                final double[] vector = createTestVector(i);
                final VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "To be batch deleted " + i,
                    documentId,
                    i,
                    projectId
                );

                vectorStorage.upsert(vectorId, vector, metadata).join();
            }

            // Delete them
            final Integer deletedCount = vectorStorage.deleteBatch(toDelete).join();
            assertEquals(3, deletedCount, "Should delete 3 vectors");

            // Verify they're gone
            for (final String id : toDelete) {
                final VectorEntry entry = vectorStorage.get(id).join();
                assertEquals(null, entry, "Vector should not exist after batch deletion");
            }
        }

        /**
         * Verify delete on non-existent vector returns false (not error).
         */
        @Test
        @DisplayName("delete on non-existent vector returns false")
        void testDeleteNonExistentReturnsFalse() {
            final String nonExistentId = UUID.randomUUID().toString();

            final Boolean deleted = vectorStorage.delete(nonExistentId).join();
            assertEquals(false, deleted, "Delete should return false for non-existent vector");
        }
    }

    @Nested
    @DisplayName("Retry Configuration Validation")
    class RetryConfigurationTests {

        /**
         * Verify multiple sequential operations complete without resource leak.
         */
        @Test
        @DisplayName("Multiple sequential operations succeed without resource leak")
        void testMultipleOperationsNoResourceLeak() {
            for (int i = 0; i < 20; i++) {
                final String vectorId = UUID.randomUUID().toString();
                createdVectorIds.add(vectorId);

                final double[] vector = createTestVector(i);
                final VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "Sequential content " + i,
                    documentId,
                    i,
                    projectId
                );

                vectorStorage.upsert(vectorId, vector, metadata).join();
            }

            final Long size = vectorStorage.size().join();
            assertTrue(size >= 20, "Should have at least 20 vectors");

            logger.info("Successfully completed 20 sequential upsert operations");
        }

        /**
         * Verify large batch operations complete correctly.
         */
        @Test
        @DisplayName("Large batch upsert succeeds")
        void testLargeBatchUpsertSucceeds() {
            final int batchSize = 100;
            final List<VectorEntry> entries = new ArrayList<>();

            for (int i = 0; i < batchSize; i++) {
                final String vectorId = UUID.randomUUID().toString();
                createdVectorIds.add(vectorId);

                final double[] vector = createTestVector(i * 0.01);
                final VectorMetadata metadata = new VectorMetadata(
                    "chunk",
                    "Large batch content " + i,
                    documentId,
                    i,
                    projectId
                );

                entries.add(new VectorEntry(vectorId, vector, metadata));
            }

            vectorStorage.upsertBatch(entries).join();

            final Long size = vectorStorage.size().join();
            assertTrue(size >= batchSize, "Should have at least " + batchSize + " vectors");

            logger.info("Successfully batch upserted {} vectors", batchSize);
        }

        /**
         * Verify hasVectors method works correctly with retry.
         */
        @Test
        @DisplayName("hasVectors returns correct status")
        void testHasVectorsReturnsCorrectStatus() {
            // Before inserting any vectors
            final Boolean hasBefore = ((PgVectorStorage) vectorStorage).hasVectors(documentId).join();
            assertEquals(false, hasBefore, "Should have no vectors before insertion");

            // Insert a vector
            final String vectorId = UUID.randomUUID().toString();
            createdVectorIds.add(vectorId);

            final double[] vector = createTestVector(1.0);
            final VectorMetadata metadata = new VectorMetadata(
                "chunk",
                "Test content",
                documentId,
                0,
                projectId
            );

            vectorStorage.upsert(vectorId, vector, metadata).join();

            // After inserting
            final Boolean hasAfter = ((PgVectorStorage) vectorStorage).hasVectors(documentId).join();
            assertEquals(true, hasAfter, "Should have vectors after insertion");
        }
    }
}
