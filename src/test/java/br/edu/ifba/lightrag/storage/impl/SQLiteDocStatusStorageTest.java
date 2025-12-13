package br.edu.ifba.lightrag.storage.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.ifba.lightrag.storage.DocStatusStorage.DocumentStatus;
import br.edu.ifba.lightrag.storage.DocStatusStorage.ProcessingStatus;

/**
 * Unit tests for SQLiteDocStatusStorage.
 * 
 * Tests verify:
 * 1. Set and get document status
 * 2. Status transitions (pending -> processing -> completed/failed)
 * 3. Batch operations
 * 4. Filter by processing status
 */
class SQLiteDocStatusStorageTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionManager connectionManager;
    private SQLiteDocStatusStorage docStatusStorage;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        connectionManager = new SQLiteConnectionManager(dbPath.toString());
        
        // Run migrations
        SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
        migrator.migrateToLatest(connectionManager.createConnection());
        
        docStatusStorage = new SQLiteDocStatusStorage(connectionManager);
        docStatusStorage.initialize().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (docStatusStorage != null) {
            docStatusStorage.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Test set and get status.
     */
    @Test
    void testSetAndGetStatus() throws Exception {
        String docId = UUID.randomUUID().toString();
        DocumentStatus status = DocumentStatus.pending(docId, "/path/to/file.pdf");
        
        docStatusStorage.setStatus(status).join();
        
        DocumentStatus retrieved = docStatusStorage.getStatus(docId).join();
        assertNotNull(retrieved, "Status should not be null");
        assertEquals(docId, retrieved.docId(), "Doc ID should match");
        assertEquals(ProcessingStatus.PENDING, retrieved.processingStatus(), "Status should be PENDING");
    }

    /**
     * Test get missing status returns null.
     */
    @Test
    void testGetMissingStatus() throws Exception {
        DocumentStatus status = docStatusStorage.getStatus("nonexistent").join();
        assertNull(status, "Should return null for missing doc");
    }

    /**
     * Test status transition to processing.
     */
    @Test
    void testStatusTransitionToProcessing() throws Exception {
        String docId = UUID.randomUUID().toString();
        DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
        docStatusStorage.setStatus(pending).join();
        
        DocumentStatus processing = pending.asProcessing();
        docStatusStorage.setStatus(processing).join();
        
        DocumentStatus retrieved = docStatusStorage.getStatus(docId).join();
        assertEquals(ProcessingStatus.PROCESSING, retrieved.processingStatus());
    }

    /**
     * Test status transition to completed with counts.
     */
    @Test
    void testStatusTransitionToCompleted() throws Exception {
        String docId = UUID.randomUUID().toString();
        DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
        docStatusStorage.setStatus(pending).join();
        
        DocumentStatus completed = pending.asCompleted(10, 5, 8);
        docStatusStorage.setStatus(completed).join();
        
        DocumentStatus retrieved = docStatusStorage.getStatus(docId).join();
        assertEquals(ProcessingStatus.COMPLETED, retrieved.processingStatus());
        assertEquals(10, retrieved.chunkCount());
        assertEquals(5, retrieved.entityCount());
        assertEquals(8, retrieved.relationCount());
    }

    /**
     * Test status transition to failed with error message.
     */
    @Test
    void testStatusTransitionToFailed() throws Exception {
        String docId = UUID.randomUUID().toString();
        DocumentStatus pending = DocumentStatus.pending(docId, "/path/file.pdf");
        docStatusStorage.setStatus(pending).join();
        
        DocumentStatus failed = pending.asFailed("Processing error: timeout");
        docStatusStorage.setStatus(failed).join();
        
        DocumentStatus retrieved = docStatusStorage.getStatus(docId).join();
        assertEquals(ProcessingStatus.FAILED, retrieved.processingStatus());
        assertEquals("Processing error: timeout", retrieved.errorMessage());
    }

    /**
     * Test batch get statuses.
     */
    @Test
    void testGetStatuses() throws Exception {
        String doc1 = UUID.randomUUID().toString();
        String doc2 = UUID.randomUUID().toString();
        
        docStatusStorage.setStatus(DocumentStatus.pending(doc1, "/path/1.pdf")).join();
        docStatusStorage.setStatus(DocumentStatus.pending(doc2, "/path/2.pdf")).join();
        
        List<DocumentStatus> statuses = docStatusStorage.getStatuses(List.of(doc1, doc2, "missing")).join();
        assertEquals(2, statuses.size(), "Should return 2 statuses");
    }

    /**
     * Test batch set statuses.
     */
    @Test
    void testSetStatuses() throws Exception {
        List<DocumentStatus> statuses = List.of(
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/1.pdf"),
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/2.pdf"),
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/3.pdf")
        );
        
        docStatusStorage.setStatuses(statuses).join();
        
        Long size = docStatusStorage.size().join();
        assertEquals(3L, size, "Should have 3 statuses");
    }

    /**
     * Test delete status.
     */
    @Test
    void testDeleteStatus() throws Exception {
        String docId = UUID.randomUUID().toString();
        docStatusStorage.setStatus(DocumentStatus.pending(docId, "/path/file.pdf")).join();
        
        Boolean deleted = docStatusStorage.deleteStatus(docId).join();
        assertTrue(deleted, "Should return true for deleted");
        
        DocumentStatus status = docStatusStorage.getStatus(docId).join();
        assertNull(status, "Status should be null after deletion");
    }

    /**
     * Test batch delete statuses.
     */
    @Test
    void testDeleteStatuses() throws Exception {
        String doc1 = UUID.randomUUID().toString();
        String doc2 = UUID.randomUUID().toString();
        String doc3 = UUID.randomUUID().toString();
        
        docStatusStorage.setStatuses(List.of(
            DocumentStatus.pending(doc1, "/path/1.pdf"),
            DocumentStatus.pending(doc2, "/path/2.pdf"),
            DocumentStatus.pending(doc3, "/path/3.pdf")
        )).join();
        
        Integer deleted = docStatusStorage.deleteStatuses(List.of(doc1, doc2)).join();
        assertEquals(2, deleted, "Should delete 2 statuses");
        assertEquals(1L, docStatusStorage.size().join(), "Should have 1 remaining");
    }

    /**
     * Test get all statuses.
     */
    @Test
    void testGetAllStatuses() throws Exception {
        docStatusStorage.setStatuses(List.of(
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/1.pdf"),
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/2.pdf")
        )).join();
        
        List<DocumentStatus> all = docStatusStorage.getAllStatuses().join();
        assertEquals(2, all.size(), "Should return all statuses");
    }

    /**
     * Test filter by processing status.
     */
    @Test
    void testGetStatusesByProcessingStatus() throws Exception {
        String pending1 = UUID.randomUUID().toString();
        String pending2 = UUID.randomUUID().toString();
        String completed1 = UUID.randomUUID().toString();
        
        docStatusStorage.setStatus(DocumentStatus.pending(pending1, "/p1.pdf")).join();
        docStatusStorage.setStatus(DocumentStatus.pending(pending2, "/p2.pdf")).join();
        docStatusStorage.setStatus(
            DocumentStatus.pending(completed1, "/c1.pdf").asCompleted(10, 5, 3)).join();
        
        List<DocumentStatus> pendingDocs = docStatusStorage.getStatusesByProcessingStatus(
            ProcessingStatus.PENDING).join();
        assertEquals(2, pendingDocs.size(), "Should have 2 pending docs");
        
        List<DocumentStatus> completedDocs = docStatusStorage.getStatusesByProcessingStatus(
            ProcessingStatus.COMPLETED).join();
        assertEquals(1, completedDocs.size(), "Should have 1 completed doc");
    }

    /**
     * Test clear removes all statuses.
     */
    @Test
    void testClear() throws Exception {
        docStatusStorage.setStatuses(List.of(
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/1.pdf"),
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path/2.pdf")
        )).join();
        
        docStatusStorage.clear().join();
        
        Long size = docStatusStorage.size().join();
        assertEquals(0L, size, "Should be empty after clear");
    }

    /**
     * Test size returns correct count.
     */
    @Test
    void testSize() throws Exception {
        assertEquals(0L, docStatusStorage.size().join(), "Empty storage should have size 0");
        
        docStatusStorage.setStatus(
            DocumentStatus.pending(UUID.randomUUID().toString(), "/path.pdf")).join();
        
        assertEquals(1L, docStatusStorage.size().join(), "Should have size 1");
    }
}
