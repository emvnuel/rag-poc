package br.edu.ifba.document;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Document operations.
 * Allows different implementations for PostgreSQL (with JTA) and SQLite (without JTA).
 */
public interface DocumentServicePort {

    /**
     * Creates a new document.
     *
     * @param document the document to create
     * @return the created document with generated ID
     */
    Document create(Document document);

    /**
     * Finds a document by ID.
     *
     * @param id the document ID
     * @return the document
     * @throws IllegalArgumentException if not found
     */
    Document findById(UUID id);

    /**
     * Deletes a document and all associated data (vectors and graph entities/relations).
     * Uses intelligent KG rebuild to preserve shared entities with other documents.
     *
     * @param documentId the document ID to delete
     * @param projectId the project ID (for graph cleanup)
     */
    void delete(UUID documentId, UUID projectId);

    /**
     * Deletes a document with option to skip KG rebuild.
     * When skipRebuild is true, shared entities are deleted instead of rebuilt.
     *
     * @param documentId the document ID to delete
     * @param projectId the project ID (for graph cleanup)
     * @param skipRebuild if true, deletes all affected entities without rebuilding
     */
    void delete(UUID documentId, UUID projectId, boolean skipRebuild);

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
     * Gets document processing progress.
     *
     * @param documentId the document ID
     * @return progress response
     */
    DocumentProgressResponse getProcessingProgress(UUID documentId);
}
