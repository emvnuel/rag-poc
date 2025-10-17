package br.edu.ifba.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.ooxml.POIXMLProperties;

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
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            return extractDocxMetadata(document);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".docx");
    }

    private Map<String, Object> extractDocxMetadata(final XWPFDocument document) {
        final Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("pageCount", document.getProperties().getExtendedProperties().getUnderlyingProperties().getPages());
        
        final POIXMLProperties.CoreProperties coreProps = document.getProperties().getCoreProperties();
        
        if (coreProps.getTitle() != null && !coreProps.getTitle().isEmpty()) {
            metadata.put("title", coreProps.getTitle());
        }
        if (coreProps.getCreator() != null && !coreProps.getCreator().isEmpty()) {
            metadata.put("author", coreProps.getCreator());
        }
        if (coreProps.getSubject() != null && !coreProps.getSubject().isEmpty()) {
            metadata.put("subject", coreProps.getSubject());
        }
        if (coreProps.getCreated() != null) {
            metadata.put("creationDate", coreProps.getCreated().toString());
        }
        if (coreProps.getModified() != null) {
            metadata.put("modificationDate", coreProps.getModified().toString());
        }
        
        return metadata;
    }
}

