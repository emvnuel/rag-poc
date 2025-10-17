package br.edu.ifba.document;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentInfoResponse(
        UUID id,
        DocumentType type,
        DocumentStatus status,
        String fileName,
        String metadata,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
