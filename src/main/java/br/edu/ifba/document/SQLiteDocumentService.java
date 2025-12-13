package br.edu.ifba.document;

import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import br.edu.ifba.lightrag.deletion.DocumentDeletionService;
import br.edu.ifba.lightrag.deletion.KnowledgeRebuildResult;
import jakarta.inject.Inject;

/**
 * SQLite implementation of DocumentServicePort.
 * Does NOT use JTA transactions since SQLite is not JTA-compatible.
 * SQLite operations are auto-committed.
 * 
 * <p><b>Note:</b> This class is now deprecated in favor of DocumentServiceProvider
 * which handles runtime selection between PostgreSQL and SQLite backends.
 * The @IfBuildProperty annotation was removed because it's a build-time annotation
 * that doesn't work when switching profiles at runtime in dev mode.</p>
 * 
 * @deprecated Use DocumentServiceProvider instead for runtime backend selection
 */
public class SQLiteDocumentService implements DocumentServicePort {

    private static final Logger LOG = Logger.getLogger(SQLiteDocumentService.class);

    @Inject
    DocumentRepositoryPort documentRepository;

    @Inject
    DocumentDeletionService documentDeletionService;

    @Override
    public Document create(final Document document) {
        documentRepository.save(document);
        return document;
    }

    @Override
    public Document findById(final UUID id) {
        return documentRepository.findByIdOrThrow(id);
    }

    @Override
    public void delete(final UUID documentId, final UUID projectId) {
        delete(documentId, projectId, false);
    }

    @Override
    public void delete(final UUID documentId, final UUID projectId, final boolean skipRebuild) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        try {
            KnowledgeRebuildResult result = documentDeletionService
                .deleteDocument(projectId, documentId, skipRebuild)
                .join();
            
            LOG.infof("Document deletion completed (skipRebuild=%s) - entities deleted: %d, rebuilt: %d, relations deleted: %d, rebuilt: %d",
                skipRebuild,
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
        documentRepository.deleteDocument(document);
    }

    @Override
    public Document findByFileName(final String fileName) {
        return documentRepository.findByFileName(fileName);
    }

    @Override
    public List<Document> findByProjectId(final UUID projectId) {
        return documentRepository.findByProjectId(projectId);
    }

    @Override
    public DocumentProgressResponse getProcessingProgress(final UUID documentId) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        // Progress is based on document status
        final double progressPercentage = switch (document.getStatus()) {
            case PROCESSED -> 100.0;
            case PROCESSING -> 50.0;
            case NOT_PROCESSED -> 0.0;
        };
        
        return new DocumentProgressResponse(progressPercentage);
    }
}
