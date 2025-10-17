package br.edu.ifba.document;

import java.util.List;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.inject.Inject;

@ApplicationScoped
public class EmbeddingRepository implements PanacheRepositoryBase<Embedding, UUID> {
    
    @Inject
    EntityManager entityManager;
    
    public boolean existsByDocumentAndChunkIndex(final UUID documentId, final Integer chunkIndex) {
        return count("document.id = ?1 AND chunkIndex = ?2", documentId, chunkIndex) > 0;
    }
    
    public List<Object[]> findSimilarEmbeddings(final String queryVector, final int limit, final int probes) {
        entityManager.createNativeQuery("SET ivfflat.probes = " + probes)
                .executeUpdate();
        
        final Query query = entityManager.createNativeQuery(
                """
                SELECT e.id, e.chunk_text, e.chunk_index, d.file_name,
                       (e.vector <=> CAST(:queryVector AS vector)) as distance
                FROM embeddings e
                JOIN documents d ON e.document_id = d.id
                ORDER BY distance
                LIMIT :limit
                """
        );
        
        query.setParameter("queryVector", queryVector);
        query.setParameter("limit", limit);
        
        return query.getResultList();
    }
    
    public List<Object[]> findSimilarEmbeddingsByProject(final String queryVector, final UUID projectId, final int limit, final int probes) {
        entityManager.createNativeQuery("SET ivfflat.probes = " + probes)
                .executeUpdate();
        
        final Query query = entityManager.createNativeQuery(
                """
                SELECT e.id, e.chunk_text, e.chunk_index, d.file_name,
                       (e.vector <=> CAST(:queryVector AS vector)) as distance
                FROM embeddings e
                JOIN documents d ON e.document_id = d.id
                WHERE d.project_id = :projectId
                ORDER BY distance
                LIMIT :limit
                """
        );
        
        query.setParameter("queryVector", queryVector);
        query.setParameter("projectId", projectId);
        query.setParameter("limit", limit);
        
        return query.getResultList();
    }
}
