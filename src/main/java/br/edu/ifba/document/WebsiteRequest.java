package br.edu.ifba.document;

import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record WebsiteRequest(
        @NotEmpty @NotNull String url,
        @NotNull UUID projectId
) {
}
