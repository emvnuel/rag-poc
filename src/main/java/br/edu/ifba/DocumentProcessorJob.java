package br.edu.ifba;

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
    private static final int BATCH_SIZE = 5;
    private static final int CHUNK_SIZE = 500;

    @Inject
    DocumentService documentService;

    @Inject
    EmbeddingRepository embeddingRepository;

    @Inject
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @ConfigProperty(name = "embedding.model")
    String embeddingModel;

    @Scheduled(every = "30s")
    public void processDocuments() {
        LOG.info("Starting document processing job...");
        
        final List<Document> documents = documentService.findAndMarkAsProcessing(BATCH_SIZE);
        
        if (documents.isEmpty()) {
            LOG.info("No documents to process.");
        } else {
            LOG.infof("Marked %d documents as PROCESSING.", documents.size());
            
            documents.forEach(this::processDocument);
        }
        
        LOG.info("Document processing job completed.");
    }

    @Transactional
    void processDocument(final Document document) {
        try {
            LOG.infof("Processing document: %s", document.getId());
            
            final List<String> chunks = TextChunker.chunkText(document.getContent(), CHUNK_SIZE);
            LOG.infof("Document chunked into %d chunks", chunks.size());
            
            for (int i = 0; i < chunks.size(); i++) {
                final String chunk = chunks.get(i);
                LOG.infof("Generating embedding for chunk %d/%d", i + 1, chunks.size());
                
                final EmbeddingRequest request = new EmbeddingRequest(embeddingModel, chunk);
                final EmbeddingResponse response = embeddingClient.embed(request);
                
                final PGvector vector = convertToPGvector(response.embeddings().getFirst());
                final Embedding embedding = new Embedding(document, i, chunk, vector, response.model());
                embeddingRepository.persist(embedding);
                
                LOG.infof("Embedding stored for chunk %d", i + 1);
            }
            
            documentService.markAsProcessed(document);
            LOG.infof("Document %s processed successfully", document.getId());
            
        } catch (Exception e) {
            LOG.errorf(e, "Error processing document %s", document.getId());
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
