package br.edu.ifba.lightrag.llm;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for Large Language Model completion.
 * Implementations should handle API calls to LLM providers (OpenAI, Anthropic, etc.).
 */
@FunctionalInterface
public interface LLMFunction {
    
    /**
     * Generate a completion from the LLM.
     *
     * @param prompt The user prompt
     * @param systemPrompt Optional system prompt for context
     * @param historyMessages Optional conversation history
     * @param kwargs Additional parameters (temperature, max_tokens, etc.)
     * @return CompletableFuture with the generated response text
     */
    CompletableFuture<String> apply(
        @NotNull String prompt,
        @Nullable String systemPrompt,
        @Nullable List<Message> historyMessages,
        @NotNull Map<String, Object> kwargs
    );
    
    /**
     * Convenience method for simple prompts without history or system prompt.
     */
    default CompletableFuture<String> apply(@NotNull String prompt) {
        return apply(prompt, null, null, Map.of());
    }
    
    /**
     * Convenience method with system prompt but no history.
     */
    default CompletableFuture<String> apply(
        @NotNull String prompt, 
        @Nullable String systemPrompt
    ) {
        return apply(prompt, systemPrompt, null, Map.of());
    }
    
    /**
     * Represents a message in the conversation history.
     */
    record Message(
        @NotNull Role role,
        @NotNull String content
    ) {
        public enum Role {
            SYSTEM, USER, ASSISTANT
        }
    }
    
    /**
     * Response metadata including token usage and model information.
     */
    record CompletionMetadata(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        @NotNull String model,
        @Nullable String finishReason
    ) {}
}
