package br.edu.ifba.document;

import java.util.List;

import br.edu.ifba.lightrag.LightRAGService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DocumentProcessorJob {

    private static final Logger LOG = Logger.getLogger(DocumentProcessorJob.class);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    LightRAGService lightragService;

    @ConfigProperty(name = "document.processor.batch.size")
    int batchSize;

    @Scheduled(every = "{document.processor.schedule.marking}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void markDocumentsAsProcessing() {
        LOG.info("Starting document marking job...");
        
        final List<Document> documents = documentRepository.findNotProcessedWithLock(batchSize);
        
        if (documents.isEmpty()) {
            LOG.info("No documents to process.");
        } else {
            LOG.infof("Found %d documents to mark as PROCESSING.", documents.size());
            documents.forEach(doc -> {
                doc.setStatus(DocumentStatus.PROCESSING);
                LOG.infof("Document %s marked as PROCESSING", doc.getId());
            });
        }
        
        LOG.info("Document marking job completed.");
    }

    @Scheduled(every = "{document.processor.schedule.processing}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processDocuments() {
        final long startTime = System.currentTimeMillis();
        LOG.info("Starting LightRAG document processing job...");
        
        final List<Document> processingDocuments = documentRepository.list("status", DocumentStatus.PROCESSING);
        
        if (processingDocuments.isEmpty()) {
            LOG.info("No documents in PROCESSING state.");
        } else {
            LOG.infof("Found %d documents to process.", processingDocuments.size());
            processingDocuments.forEach(doc -> processDocument(doc.getId()));
        }
        
        final long executionTime = System.currentTimeMillis() - startTime;
        LOG.infof("LightRAG document processing job completed in %d ms", executionTime);
    }

    void processDocument(final java.util.UUID documentId) {
        try {
            final Document document = documentRepository.findById(documentId);
            if (document == null) {
                LOG.errorf("Document %s not found", documentId);
                return;
            }

            // PRE-CHECK: Query database to see if vectors already exist
            // This prevents duplicate processing in case of race conditions
            boolean hasVectors = lightragService.hasDocumentVectors(documentId).join();
            if (hasVectors) {
                LOG.infof("Document %s already has vectors in database (detected race condition), skipping LightRAG processing", 
                         documentId);
                markAsProcessed(documentId);
                return;
            }

            LOG.infof("Processing document %s through LightRAG - fileName: %s, projectId: %s", 
                    documentId, document.getFileName(), document.getProject().getId());
            
            // Insert document into LightRAG knowledge graph
            // This will handle chunking, entity extraction, and graph construction
            lightragService.insertDocument(
                    documentId, 
                    document.getContent(), 
                    document.getFileName(), 
                    document.getProject().getId()
            ).join();
            
            // Mark as processed
            markAsProcessed(documentId);
            
        } catch (Exception e) {
            LOG.errorf(e, "Error processing document %s through LightRAG", documentId);
            try {
                markAsFailed(documentId);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to mark document %s as failed (application may be shutting down)", documentId);
            }
        }
    }

    @Transactional
    void markAsProcessed(final java.util.UUID documentId) {
        final Document document = documentRepository.findById(documentId);
        if (document != null) {
            document.setStatus(DocumentStatus.PROCESSED);
            LOG.infof("Document %s marked as PROCESSED", documentId);
        }
    }

    @Transactional
    void markAsFailed(final java.util.UUID documentId) {
        final Document document = documentRepository.findById(documentId);
        if (document != null) {
            document.setStatus(DocumentStatus.NOT_PROCESSED);
            LOG.infof("Document %s marked as NOT_PROCESSED for retry", documentId);
        }
    }
}
