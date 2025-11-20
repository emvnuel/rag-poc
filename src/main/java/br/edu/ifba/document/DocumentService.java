package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DocumentService {

    @Inject
    DocumentRepository documentRepository;

    @Inject
    br.edu.ifba.lightrag.LightRAGService lightRAGService;

    @Transactional
    public Document create(final Document document) {
        documentRepository.persist(document);
        return document;
    }

    public Document findById(final java.util.UUID id) {
        return documentRepository.findByIdOrThrow(id);
    }
    
    /**
     * Deletes a document and all associated data (vectors and graph entities/relations).
     * 
     * @param documentId The document ID to delete
     * @param projectId The project ID (for graph cleanup)
     */
    @Transactional
    public void delete(final java.util.UUID documentId, final java.util.UUID projectId) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        // Delete graph entities and relations for this document
        try {
            lightRAGService.deleteDocumentFromGraph(projectId.toString(), documentId.toString())
                .join(); // Wait for async completion
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
        }
        
        // Delete the document (vectors will cascade automatically via FK constraint)
        documentRepository.delete(document);
    }

    public Document findByFileName(final String fileName) {
        return documentRepository.findByFileName(fileName);
    }

    public java.util.List<Document> findByProjectId(final java.util.UUID projectId) {
        return documentRepository.findByProjectId(projectId);
    }

    /**
     * Gets document processing progress.
     * With LightRAG, progress is simplified to document status-based tracking.
     * 
     * @param documentId The document ID
     * @return Progress response (100% if processed, 0% otherwise)
     */
    public DocumentProgressResponse getProcessingProgress(final java.util.UUID documentId) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        // LightRAG processes documents as a whole, not in chunks
        // Progress is based on document status
        final double progressPercentage = switch (document.getStatus()) {
            case PROCESSED -> 100.0;
            case PROCESSING -> 50.0;
            case NOT_PROCESSED -> 0.0;
        };
        
        return new DocumentProgressResponse(progressPercentage);
    }
}
