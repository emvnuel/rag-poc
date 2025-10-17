package br.edu.ifba.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
    String role,
    String content
) {
}
