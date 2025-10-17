package br.edu.ifba.chat;

import java.util.List;

import br.edu.ifba.document.SearchResult;

public record ChatResponse(
    String response,
    List<ChatMessage> messages,
    List<SearchResult> sources,
    String model,
    Long totalDuration,
    Long promptEvalCount,
    Long evalCount
) {
}
