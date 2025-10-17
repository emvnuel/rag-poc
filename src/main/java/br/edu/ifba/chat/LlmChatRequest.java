package br.edu.ifba.chat;

import java.util.List;

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
    Double topP
) {
    public LlmChatRequest(final String model, final List<ChatMessage> messages) {
        this(model, messages, false, null, null, null);
    }
}
