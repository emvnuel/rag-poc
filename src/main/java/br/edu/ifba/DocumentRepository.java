package br.edu.ifba;

import java.util.List;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

@ApplicationScoped
public class DocumentRepository implements PanacheRepositoryBase<Document, UUID> {

    public Document findByFileName(final String fileName) {
        return find("fileName", fileName).firstResult();
    }

    public Document findByIdOrThrow(final UUID id) {
        final Document document = findById(id);
        if (document == null) {
            throw new IllegalArgumentException("Document not found with id: " + id);
        }
        return document;
    }

    public List<Document> findNotProcessed(final int limit) {
        return find("status", DocumentStatus.NOT_PROCESSED)
                .page(Page.ofSize(limit))
                .list();
    }

    public List<Document> findNotProcessedWithLock(final int limit) {
        return find("status = ?1 ORDER BY createdAt", DocumentStatus.NOT_PROCESSED)
                .page(Page.ofSize(limit))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
    }
}
