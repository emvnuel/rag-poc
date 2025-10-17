package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WordDocumentExtractor implements DocumentExtractor {

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".docx");
    }
}

