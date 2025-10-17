package br.edu.ifba;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.IOException;
import java.io.InputStream;

@ApplicationScoped
class DocDocumentExtractor implements DocumentExtractor {

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".doc");
    }
}
