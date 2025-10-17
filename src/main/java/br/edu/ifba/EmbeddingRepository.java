package br.edu.ifba;

import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmbeddingRepository implements PanacheRepositoryBase<Embedding, UUID> {
    
    public boolean existsByDocumentAndChunkIndex(final UUID documentId, final Integer chunkIndex) {
        return count("document.id = ?1 AND chunkIndex = ?2", documentId, chunkIndex) > 0;
    }
}
