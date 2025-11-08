package br.edu.ifba.lightrag.storage;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for document status storage operations.
 * Used for tracking document processing status and metadata.
 * 
 * Implementations: JsonDocStatusStorage, PGDocStatusStorage, MongoDocStatusStorage
 */
public interface DocStatusStorage extends AutoCloseable {
    
    /**
     * Initializes the document status storage backend.
     * Must be called before any other operations.
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Gets the status of a document.
     *
     * @param docId the document ID
     * @return the document status, or null if not found
     */
    CompletableFuture<DocumentStatus> getStatus(@NotNull String docId);
    
    /**
     * Gets the status of multiple documents.
     *
     * @param docIds the document IDs
     * @return a list of document statuses (missing documents will not be in the list)
     */
    CompletableFuture<List<DocumentStatus>> getStatuses(@NotNull List<String> docIds);
    
    /**
     * Sets the status of a document.
     *
     * @param status the document status to set
     */
    CompletableFuture<Void> setStatus(@NotNull DocumentStatus status);
    
    /**
     * Sets the status of multiple documents.
     *
     * @param statuses the document statuses to set
     */
    CompletableFuture<Void> setStatuses(@NotNull List<DocumentStatus> statuses);
    
    /**
     * Deletes the status of a document.
     *
     * @param docId the document ID
     * @return true if the status was deleted, false if it didn't exist
     */
    CompletableFuture<Boolean> deleteStatus(@NotNull String docId);
    
    /**
     * Deletes the status of multiple documents.
     *
     * @param docIds the document IDs
     * @return the number of statuses that were deleted
     */
    CompletableFuture<Integer> deleteStatuses(@NotNull List<String> docIds);
    
    /**
     * Gets all document statuses.
     *
     * @return a list of all document statuses
     */
    CompletableFuture<List<DocumentStatus>> getAllStatuses();
    
    /**
     * Gets all document statuses with a specific processing status.
     *
     * @param processingStatus the processing status to filter by
     * @return a list of matching document statuses
     */
    CompletableFuture<List<DocumentStatus>> getStatusesByProcessingStatus(@NotNull ProcessingStatus processingStatus);
    
    /**
     * Clears all document statuses.
     */
    CompletableFuture<Void> clear();
    
    /**
     * Gets the number of document statuses.
     *
     * @return the number of document statuses
     */
    CompletableFuture<Long> size();
    
    /**
     * Closes the storage and releases resources.
     */
    @Override
    void close() throws Exception;
    
    /**
     * Represents the processing status of a document.
     */
    enum ProcessingStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
    
    /**
     * Represents the status and metadata of a document.
     */
    record DocumentStatus(
            @NotNull String docId,
            @NotNull ProcessingStatus processingStatus,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt,
            String filePath,
            int chunkCount,
            int entityCount,
            int relationCount,
            String errorMessage) {
        
        /**
         * Creates a new DocumentStatus with PENDING status.
         */
        public static DocumentStatus pending(@NotNull String docId, String filePath) {
            Instant now = Instant.now();
            return new DocumentStatus(
                    docId,
                    ProcessingStatus.PENDING,
                    now,
                    now,
                    filePath,
                    0,
                    0,
                    0,
                    null
            );
        }
        
        /**
         * Creates a new DocumentStatus marking this as processing.
         */
        public DocumentStatus asProcessing() {
            return new DocumentStatus(
                    docId,
                    ProcessingStatus.PROCESSING,
                    createdAt,
                    Instant.now(),
                    filePath,
                    chunkCount,
                    entityCount,
                    relationCount,
                    null
            );
        }
        
        /**
         * Creates a new DocumentStatus marking this as completed.
         */
        public DocumentStatus asCompleted(int chunkCount, int entityCount, int relationCount) {
            return new DocumentStatus(
                    docId,
                    ProcessingStatus.COMPLETED,
                    createdAt,
                    Instant.now(),
                    filePath,
                    chunkCount,
                    entityCount,
                    relationCount,
                    null
            );
        }
        
        /**
         * Creates a new DocumentStatus marking this as failed.
         */
        public DocumentStatus asFailed(String errorMessage) {
            return new DocumentStatus(
                    docId,
                    ProcessingStatus.FAILED,
                    createdAt,
                    Instant.now(),
                    filePath,
                    chunkCount,
                    entityCount,
                    relationCount,
                    errorMessage
            );
        }
    }
}
