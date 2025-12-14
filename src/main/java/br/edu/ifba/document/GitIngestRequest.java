package br.edu.ifba.document;

/**
 * Request DTO for Git repository ingestion.
 */
public record GitIngestRequest(
        String repoUrl,
        String branch,
        String patterns
) {
    public GitIngestRequest {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }
    }
}
