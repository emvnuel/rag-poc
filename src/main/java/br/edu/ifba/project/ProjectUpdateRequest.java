package br.edu.ifba.project;

import jakarta.validation.constraints.NotBlank;

public record ProjectUpdateRequest(
        @NotBlank(message = "Name is required")
        String name
) {
}
