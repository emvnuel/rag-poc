package br.edu.ifba.document;

import java.util.UUID;

public record SearchResult(
        UUID id,
        String chunkText,
        Integer chunkIndex,
        String fileName,
        Double distance) {
}
