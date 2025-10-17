package br.edu.ifba.document;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SearchRequest(
        @NotBlank(message = "Query cannot be blank")
        String query,
        
        @NotNull(message = "Project ID is required")
        UUID projectId,
        
        @Positive(message = "Limit must be positive")
        Integer limit) {
}
