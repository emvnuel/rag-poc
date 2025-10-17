package br.edu.ifba.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmChatResponse(
    String id,
    String object,
    Long created,
    String model,
    List<Choice> choices,
    Usage usage
) {
    public record Choice(
        Integer index,
        ChatMessage message,
        
        @JsonProperty("finish_reason")
        String finishReason
    ) {}
    
    public record Usage(
        @JsonProperty("prompt_tokens")
        Integer promptTokens,
        
        @JsonProperty("completion_tokens")
        Integer completionTokens,
        
        @JsonProperty("total_tokens")
        Integer totalTokens
    ) {}
}
