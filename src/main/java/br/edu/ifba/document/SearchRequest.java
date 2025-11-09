package br.edu.ifba.document;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SearchRequest(
        @NotBlank(message = "Query cannot be blank")
        String query,
        
        @NotNull(message = "Project ID is required")
        UUID projectId) {
}
