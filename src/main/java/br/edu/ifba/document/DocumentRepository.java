package br.edu.ifba.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

/**
 * Hibernate/PostgreSQL implementation of DocumentRepositoryPort.
 * 
 * NOTE: This is NOT a CDI bean directly. It's instantiated by DocumentServiceProvider
 * based on runtime configuration (lightrag.storage.backend property).
 * This allows switching between PostgreSQL and SQLite backends at runtime in dev mode.
 */
public class DocumentRepository implements PanacheRepositoryBase<Document, UUID>, DocumentRepositoryPort {

    @Override
    public void save(final Document document) {
        persist(document);
    }

    @Override
    public Optional<Document> findDocumentById(final UUID id) {
        return Optional.ofNullable(findById(id));
    }

    @Override
    public Document findByFileName(final String fileName) {
        return find("fileName", fileName).firstResult();
    }

    @Override
    public Document findByIdOrThrow(final UUID id) {
        final Document document = findById(id);
        if (document == null) {
            throw new IllegalArgumentException("Document not found with id: " + id);
        }
        return document;
    }

    @Override
    public List<Document> findNotProcessed(final int limit) {
        return find("status", DocumentStatus.NOT_PROCESSED)
                .page(Page.ofSize(limit))
                .list();
    }

    @Override
    public List<Document> findNotProcessedWithLock(final int limit) {
        return find("status = ?1 ORDER BY createdAt", DocumentStatus.NOT_PROCESSED)
                .page(Page.ofSize(limit))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
    }

    @Override
    public List<Document> findByProjectId(final UUID projectId) {
        return find("project.id", projectId).list();
    }

    @Override
    public void deleteDocument(final Document document) {
        delete(document);
    }

    @Override
    public void update(final Document document) {
        // In JPA, changes are automatically tracked
        getEntityManager().merge(document);
    }

    @Override
    public List<Document> findByStatus(final DocumentStatus status) {
        return find("status", status).list();
    }

    @Override
    public void flush() {
        getEntityManager().flush();
    }
}
