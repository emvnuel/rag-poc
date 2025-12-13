package br.edu.ifba.chat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmChatRequest(
    String model,
    List<ChatMessage> messages,
    Boolean stream,
    
    @JsonProperty("max_tokens")
    Integer maxTokens,
    
    Double temperature,
    
    @JsonProperty("top_p")
    Double topP,
    
    /**
     * OpenRouter reasoning configuration.
     * Set to {"effort": "none"} to disable reasoning tokens.
     * @see <a href="https://openrouter.ai/docs/guides/best-practices/reasoning-tokens">OpenRouter Reasoning Tokens</a>
     */
    Map<String, Object> reasoning
) {
    /**
     * Default reasoning configuration that disables reasoning tokens.
     */
    private static final Map<String, Object> REASONING_DISABLED = Map.of("effort", "none");

    public LlmChatRequest(final String model, final List<ChatMessage> messages) {
        this(model, messages, false, null, null, null, REASONING_DISABLED);
    }

    public LlmChatRequest(
            final String model,
            final List<ChatMessage> messages,
            final Boolean stream,
            final Integer maxTokens,
            final Double temperature,
            final Double topP) {
        this(model, messages, stream, maxTokens, temperature, topP, REASONING_DISABLED);
    }
}
