package br.edu.ifba.document;

import java.util.UUID;

/**
 * Response DTO for Git repository ingestion.
 */
public record GitIngestResponse(
        int totalFiles,
        int processedFiles,
        String status,
        UUID projectId
) {}
