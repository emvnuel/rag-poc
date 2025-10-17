package br.edu.ifba.document;

import br.edu.ifba.exception.FileUploadException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class DocumentExtractorFactory {

    @Inject
    Instance<DocumentExtractor> extractors;

    public DocumentExtractor getExtractor(final String fileName) {
        return extractors.stream()
                .filter(extractor -> extractor.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new FileUploadException("Unsupported file type: " + fileName));
    }
}
