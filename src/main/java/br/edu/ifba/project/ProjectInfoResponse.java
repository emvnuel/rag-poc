package br.edu.ifba.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectInfoResponse(
        UUID id,
        String name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long documentCount
) {
}
