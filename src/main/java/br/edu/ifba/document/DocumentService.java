package br.edu.ifba.document;

import br.edu.ifba.lightrag.deletion.DocumentDeletionService;
import br.edu.ifba.lightrag.deletion.KnowledgeRebuildResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DocumentService {

    private static final Logger LOG = Logger.getLogger(DocumentService.class);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentDeletionService documentDeletionService;

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
     * Uses intelligent KG rebuild to preserve shared entities with other documents.
     * 
     * @param documentId The document ID to delete
     * @param projectId The project ID (for graph cleanup)
     */
    @Transactional
    public void delete(final java.util.UUID documentId, final java.util.UUID projectId) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        // Delete graph entities and relations for this document with intelligent rebuild
        try {
            KnowledgeRebuildResult result = documentDeletionService
                .deleteDocument(projectId, documentId, false)
                .join(); // Wait for async completion
            
            LOG.infof("Document deletion completed - entities deleted: %d, rebuilt: %d, relations deleted: %d, rebuilt: %d",
                result.entitiesDeleted().size(),
                result.entitiesRebuilt().size(),
                result.relationsDeleted(),
                result.relationsRebuilt());
            
            if (!result.errors().isEmpty()) {
                LOG.warnf("Document deletion had %d errors: %s", 
                    result.errors().size(), 
                    String.join("; ", result.errors()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
        }
        
        // Delete the document (vectors will cascade automatically via FK constraint)
        documentRepository.delete(document);
    }
    
    /**
     * Deletes a document with option to skip KG rebuild.
     * When skipRebuild is true, shared entities are deleted instead of rebuilt.
     * 
     * @param documentId The document ID to delete
     * @param projectId The project ID (for graph cleanup)
     * @param skipRebuild If true, deletes all affected entities without rebuilding
     */
    @Transactional
    public void delete(final java.util.UUID documentId, final java.util.UUID projectId, final boolean skipRebuild) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        try {
            KnowledgeRebuildResult result = documentDeletionService
                .deleteDocument(projectId, documentId, skipRebuild)
                .join();
            
            LOG.infof("Document deletion completed (skipRebuild=%s) - entities deleted: %d, rebuilt: %d",
                skipRebuild,
                result.entitiesDeleted().size(),
                result.entitiesRebuilt().size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete graph data for document: " + documentId, e);
        }
        
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
