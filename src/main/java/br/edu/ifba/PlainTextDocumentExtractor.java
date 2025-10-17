package br.edu.ifba;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlainTextDocumentExtractor implements DocumentExtractor {

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    @Override
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            final String text = reader.lines().collect(Collectors.joining("\n"));
            return extractTextMetadata(text);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".txt") || fileName.endsWith(".md");
    }

    private Map<String, Object> extractTextMetadata(final String text) {
        final Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("characterCount", text.length());
        metadata.put("wordCount", countWords(text));
        metadata.put("lineCount", text.split("\n").length);
        
        return metadata;
    }

    private int countWords(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
