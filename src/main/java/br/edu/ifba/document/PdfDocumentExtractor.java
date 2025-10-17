package br.edu.ifba.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PdfDocumentExtractor implements DocumentExtractor {

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    @Override
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            return extractPdfMetadata(document);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".pdf");
    }

    private Map<String, Object> extractPdfMetadata(final PDDocument document) {
        final Map<String, Object> metadata = new HashMap<>();
        final PDDocumentInformation info = document.getDocumentInformation();
        
        metadata.put("pageCount", document.getNumberOfPages());
        
        if (info.getTitle() != null && !info.getTitle().isEmpty()) {
            metadata.put("title", info.getTitle());
        }
        if (info.getAuthor() != null && !info.getAuthor().isEmpty()) {
            metadata.put("author", info.getAuthor());
        }
        if (info.getSubject() != null && !info.getSubject().isEmpty()) {
            metadata.put("subject", info.getSubject());
        }
        if (info.getCreationDate() != null) {
            metadata.put("creationDate", info.getCreationDate().getTime().toString());
        }
        if (info.getModificationDate() != null) {
            metadata.put("modificationDate", info.getModificationDate().getTime().toString());
        }
        
        return metadata;
    }
}
