package br.edu.ifba.document;

import java.util.List;

import com.pgvector.PGvector;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DocumentProcessorJob {

    private static final Logger LOG = Logger.getLogger(DocumentProcessorJob.class);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    EmbeddingRepository embeddingRepository;

    @Inject
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @ConfigProperty(name = "embedding.model")
    String embeddingModel;

    @ConfigProperty(name = "document.processor.batch.size")
    int batchSize;

    @ConfigProperty(name = "document.processor.chunk.size")
    int chunkSize;

    @ConfigProperty(name = "document.processor.chunks.per.batch")
    int chunksPerBatch;

    @Scheduled(every = "{document.processor.schedule.marking}")
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

    @Scheduled(every = "{document.processor.schedule.processing}")
    public void processDocumentChunks() {
        LOG.info("Starting chunk processing job...");
        
        final List<Document> processingDocuments = documentRepository.list("status", DocumentStatus.PROCESSING);
        
        if (processingDocuments.isEmpty()) {
            LOG.info("No documents in PROCESSING state.");
        } else {
            LOG.infof("Found %d documents to process chunks.", processingDocuments.size());
            processingDocuments.forEach(doc -> processChunks(doc.getId()));
        }
        
        LOG.info("Chunk processing job completed.");
    }

    void processChunks(final java.util.UUID documentId) {
        try {
            final Document document = documentRepository.findById(documentId);
            if (document == null) {
                LOG.errorf("Document %s not found", documentId);
                return;
            }

            final List<String> chunks = TextChunker.chunkText(document.getContent(), chunkSize);
            LOG.infof("Document %s has %d chunks", documentId, chunks.size());
            
            int startIndex = findNextChunkToProcess(documentId, chunks.size());
            if (startIndex >= chunks.size()) {
                checkAndMarkAsProcessed(documentId, chunks.size());
                return;
            }
            
            int endIndex = Math.min(startIndex + chunksPerBatch, chunks.size());
            LOG.infof("Processing chunks %d to %d of document %s", startIndex, endIndex - 1, documentId);
            
            for (int i = startIndex; i < endIndex; i++) {
                processChunk(documentId, i, chunks.get(i), chunks.size());
            }
            
            if (endIndex >= chunks.size()) {
                checkAndMarkAsProcessed(documentId, chunks.size());
            }
            
        } catch (Exception e) {
            LOG.errorf(e, "Error processing document %s", documentId);
            markAsFailed(documentId);
        }
    }

    int findNextChunkToProcess(final java.util.UUID documentId, final int totalChunks) {
        for (int i = 0; i < totalChunks; i++) {
            if (!embeddingRepository.existsByDocumentAndChunkIndex(documentId, i)) {
                return i;
            }
        }
        return totalChunks;
    }

    @Transactional
    void processChunk(final java.util.UUID documentId, final int chunkIndex, 
                      final String chunkText, final int totalChunks) {
        try {
            if (embeddingRepository.existsByDocumentAndChunkIndex(documentId, chunkIndex)) {
                LOG.infof("Embedding already exists for chunk %d/%d, skipping", chunkIndex + 1, totalChunks);
                return;
            }
            
            final Document document = documentRepository.findById(documentId);
            if (document == null) {
                LOG.errorf("Document %s not found", documentId);
                return;
            }
            
            LOG.infof("Generating embedding for chunk %d/%d", chunkIndex + 1, totalChunks);
            
            final EmbeddingRequest request = new EmbeddingRequest(embeddingModel, chunkText);
            final EmbeddingResponse response = embeddingClient.embed(request);
            
            final PGvector vector = convertToPGvector(response.embeddings().getFirst());
            final Embedding embedding = new Embedding(document, chunkIndex, chunkText, vector, response.model());
            embeddingRepository.persist(embedding);
            
            LOG.infof("Embedding stored for chunk %d/%d", chunkIndex + 1, totalChunks);
            
        } catch (org.hibernate.exception.ConstraintViolationException e) {
            LOG.warnf("Duplicate chunk %d for document %s detected (concurrent processing), skipping", 
                    chunkIndex, documentId);
        } catch (jakarta.persistence.PersistenceException e) {
            if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                LOG.warnf("Duplicate chunk %d for document %s detected (concurrent processing), skipping", 
                        chunkIndex, documentId);
            } else {
                LOG.errorf(e, "Error processing chunk %d of document %s", chunkIndex, documentId);
                throw e;
            }
        } catch (Exception e) {
            LOG.errorf(e, "Error processing chunk %d of document %s", chunkIndex, documentId);
            throw e;
        }
    }

    @Transactional
    void checkAndMarkAsProcessed(final java.util.UUID documentId, final int expectedChunks) {
        final Document document = documentRepository.findById(documentId);
        if (document == null) {
            return;
        }

        final long embeddingCount = embeddingRepository.count("document.id", documentId);
        
        if (embeddingCount >= expectedChunks) {
            document.setStatus(DocumentStatus.PROCESSED);
            LOG.infof("Document %s marked as PROCESSED (%d/%d chunks complete)", 
                    documentId, embeddingCount, expectedChunks);
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

    private PGvector convertToPGvector(final List<Double> vector) {
        final float[] floatArray = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            floatArray[i] = vector.get(i).floatValue();
        }
        return new PGvector(floatArray);
    }
}
