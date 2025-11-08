package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DocumentService {

    @Inject
    DocumentRepository documentRepository;

    @Transactional
    public Document create(final Document document) {
        documentRepository.persist(document);
        return document;
    }

    public Document findById(final java.util.UUID id) {
        return documentRepository.findByIdOrThrow(id);
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
