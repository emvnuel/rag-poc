package br.edu.ifba;

import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

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
}
