package br.edu.ifba;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DocumentService {

    @Inject
    DocumentRepository documentRepository;

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
}
