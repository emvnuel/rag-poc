package br.edu.ifba.document;

import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record TextRequest(
        @NotEmpty @NotNull String text,
        @NotNull UUID projectId,
        String filename,
        DocumentType documentType
) {
}
