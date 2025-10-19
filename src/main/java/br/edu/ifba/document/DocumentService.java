package br.edu.ifba.document;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DocumentService {

    @Inject
    DocumentRepository documentRepository;

    @Inject
    EmbeddingRepository embeddingRepository;

    @ConfigProperty(name = "document.processor.chunk.size")
    int chunkSize;

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

    public DocumentProgressResponse getProcessingProgress(final java.util.UUID documentId) {
        final Document document = documentRepository.findByIdOrThrow(documentId);
        
        final List<String> chunks = TextChunker.chunkText(document.getContent(), chunkSize);
        final int totalChunks = chunks.size();
        
        if (totalChunks == 0) {
            return new DocumentProgressResponse(100.0);
        }
        
        final long processedChunks = embeddingRepository.count("document.id", documentId);
        final double progressPercentage = (double) processedChunks / totalChunks * 100.0;
        
        return new DocumentProgressResponse(progressPercentage);
    }
}
