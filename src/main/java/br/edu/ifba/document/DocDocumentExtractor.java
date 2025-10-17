package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hpsf.SummaryInformation;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

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
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream)) {
            return extractDocMetadata(document);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".doc");
    }

    private Map<String, Object> extractDocMetadata(final HWPFDocument document) {
        final Map<String, Object> metadata = new HashMap<>();
        
        final SummaryInformation summaryInfo = document.getSummaryInformation();
        
        if (summaryInfo != null) {
            if (summaryInfo.getPageCount() > 0) {
                metadata.put("pageCount", summaryInfo.getPageCount());
            }
            if (summaryInfo.getTitle() != null && !summaryInfo.getTitle().isEmpty()) {
                metadata.put("title", summaryInfo.getTitle());
            }
            if (summaryInfo.getAuthor() != null && !summaryInfo.getAuthor().isEmpty()) {
                metadata.put("author", summaryInfo.getAuthor());
            }
            if (summaryInfo.getSubject() != null && !summaryInfo.getSubject().isEmpty()) {
                metadata.put("subject", summaryInfo.getSubject());
            }
            if (summaryInfo.getCreateDateTime() != null) {
                metadata.put("creationDate", summaryInfo.getCreateDateTime().toString());
            }
            if (summaryInfo.getLastSaveDateTime() != null) {
                metadata.put("modificationDate", summaryInfo.getLastSaveDateTime().toString());
            }
        }
        
        return metadata;
    }
}
