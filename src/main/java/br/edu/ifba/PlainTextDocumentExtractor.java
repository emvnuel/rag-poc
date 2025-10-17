package br.edu.ifba;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    public boolean supports(final String fileName) {
        return fileName.endsWith(".txt") || fileName.endsWith(".md");
    }
}
