package br.edu.ifba.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository abstraction for Document persistence operations.
 * 
 * <p>This interface allows switching between different persistence backends
 * (Hibernate/PostgreSQL or SQLite) via configuration.</p>
 */
public interface DocumentRepositoryPort {

    /**
     * Saves a new document.
     * 
     * @param document the document to save
     */
    void save(Document document);

    /**
     * Finds a document by ID.
     * 
     * @param id the document ID
     * @return the document wrapped in Optional
     */
    Optional<Document> findDocumentById(UUID id);

    /**
     * Finds a document by ID or throws if not found.
     * 
     * @param id the document ID
     * @return the document
     * @throws IllegalArgumentException if document not found
     */
    Document findByIdOrThrow(UUID id);

    /**
     * Finds a document by file name.
     * 
     * @param fileName the file name
     * @return the document, or null if not found
     */
    Document findByFileName(String fileName);

    /**
     * Finds all documents for a project.
     * 
     * @param projectId the project ID
     * @return list of documents
     */
    List<Document> findByProjectId(UUID projectId);

    /**
     * Finds documents that have not been processed yet.
     * 
     * @param limit maximum number of documents to return
     * @return list of unprocessed documents
     */
    List<Document> findNotProcessed(int limit);

    /**
     * Finds documents not processed with pessimistic locking.
     * 
     * @param limit maximum number of documents to return
     * @return list of unprocessed documents
     */
    List<Document> findNotProcessedWithLock(int limit);

    /**
     * Deletes a document.
     * 
     * @param document the document to delete
     */
    void deleteDocument(Document document);

    /**
     * Updates a document (persists changes).
     * 
     * @param document the document to update
     */
    void update(Document document);

    /**
     * Finds all documents with a specific status.
     * 
     * @param status the document status
     * @return list of documents with the status
     */
    List<Document> findByStatus(DocumentStatus status);

    /**
     * Flushes pending changes to the database.
     */
    void flush();
}
