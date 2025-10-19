package br.edu.ifba.document;

import java.util.ArrayList;
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
    public void processDocumentChunks() {
        final long startTime = System.currentTimeMillis();
        LOG.info("Starting chunk processing job...");
        
        final List<Document> processingDocuments = documentRepository.list("status", DocumentStatus.PROCESSING);
        
        if (processingDocuments.isEmpty()) {
            LOG.info("No documents in PROCESSING state.");
        } else {
            LOG.infof("Found %d documents to process chunks.", processingDocuments.size());
            processingDocuments.forEach(doc -> processChunks(doc.getId()));
        }
        
        final long executionTime = System.currentTimeMillis() - startTime;
        LOG.infof("Chunk processing job completed in %d ms", executionTime);
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
            
            processChunksBatch(documentId, chunks, startIndex, endIndex);
            
            if (endIndex >= chunks.size()) {
                checkAndMarkAsProcessed(documentId, chunks.size());
            }
            
        } catch (Exception e) {
            LOG.errorf(e, "Error processing document %s", documentId);
            try {
                markAsFailed(documentId);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to mark document %s as failed (application may be shutting down)", documentId);
            }
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

    void processChunksBatch(final java.util.UUID documentId, final List<String> chunks, 
                            final int startIndex, final int endIndex) {
        try {
            final List<String> chunksToProcess = new ArrayList<>();
            final List<Integer> chunkIndices = new ArrayList<>();
            
            for (int i = startIndex; i < endIndex; i++) {
                if (!embeddingRepository.existsByDocumentAndChunkIndex(documentId, i)) {
                    chunksToProcess.add(chunks.get(i));
                    chunkIndices.add(i);
                }
            }
            
            if (chunksToProcess.isEmpty()) {
                LOG.infof("All chunks %d to %d already processed, skipping batch", startIndex, endIndex - 1);
                return;
            }
            
            LOG.infof("Generating embeddings for %d chunks in batch (indices %d to %d)", 
                    chunksToProcess.size(), startIndex, endIndex - 1);
            
            final EmbeddingRequest request = new EmbeddingRequest(embeddingModel, chunksToProcess);
            final EmbeddingResponse response = embeddingClient.embed(request);
            
            storeEmbeddingsBatch(documentId, chunks.size(), chunkIndices, chunksToProcess, response);
            
        } catch (Exception e) {
            LOG.errorf(e, "Error processing batch for document %s", documentId);
            throw e;
        }
    }

    @Transactional
    void storeEmbeddingsBatch(final java.util.UUID documentId, final int totalChunks,
                              final List<Integer> chunkIndices, final List<String> chunksToProcess,
                              final EmbeddingResponse response) {
        final Document document = documentRepository.findById(documentId);
        if (document == null) {
            LOG.errorf("Document %s not found", documentId);
            return;
        }
        
        int successCount = 0;
        for (int i = 0; i < response.data().size(); i++) {
            final EmbeddingResponse.Embedding embeddingData = response.data().get(i);
            final int chunkIndex = chunkIndices.get(i);
            final String chunkText = chunksToProcess.get(i);
            final PGvector vector = convertToPGvector(embeddingData.embedding());
            
            if (!embeddingRepository.existsByDocumentAndChunkIndex(documentId, chunkIndex)) {
                final Embedding embedding = new Embedding(document, chunkIndex, chunkText, vector, response.model());
                embeddingRepository.persist(embedding);
                successCount++;
                LOG.infof("Embedding stored for chunk %d/%d", chunkIndex + 1, totalChunks);
            } else {
                LOG.infof("Embedding already exists for chunk %d/%d (race condition), skipping", chunkIndex + 1, totalChunks);
            }
        }
        
        LOG.infof("Batch embedding completed: %d stored, %d skipped", successCount, chunksToProcess.size() - successCount);
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
