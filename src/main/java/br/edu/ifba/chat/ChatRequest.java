package br.edu.ifba.chat;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatRequest(
    @NotNull(message = "Project ID is required")
    UUID projectId,
    
    @NotBlank(message = "Message is required")
    String message,
    
    List<ChatMessage> history,
    
    /**
     * Optional flag to enable/disable reranking for this request.
     * If null, uses the global configuration (lightrag.rerank.enabled).
     */
    Boolean rerank
    
) {
    public ChatRequest(final UUID projectId, final String message) {
        this(projectId, message, null, null);
    }
    
    public ChatRequest(final UUID projectId, final String message, final List<ChatMessage> history) {
        this(projectId, message, history, null);
    }
}
