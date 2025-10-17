package br.edu.ifba.project;

import jakarta.validation.constraints.NotBlank;

public record ProjectCreateRequest(
        @NotBlank(message = "Name is required")
        String name
) {
}
