package br.edu.ifba.chat;

import java.util.List;

import br.edu.ifba.document.SearchResult;

/**
 * Response object returned by the chat completion endpoint.
 * 
 * <p>Contains the AI-generated response text, conversation history, and source metadata
 * for traceability and citation verification. The {@code sources} field includes
 * document identifiers and chunk indices that enable users to trace AI responses
 * back to specific document chunks.</p>
 * 
 * <h3>Source Metadata (Feature 003: Chat Response Chunk Metadata)</h3>
 * <p>Each entry in the {@code sources} array includes:</p>
 * <ul>
 *   <li><b>id</b>: Document UUID (null for synthesized answers)</li>
 *   <li><b>chunkIndex</b>: Chunk position within document (null for synthesized answers)</li>
 *   <li><b>chunkText</b>: Text content used to generate the response</li>
 *   <li><b>source</b>: Human-readable label (e.g., filename or "LightRAG Answer")</li>
 *   <li><b>distance</b>: Vector similarity score (null for synthesized answers)</li>
 * </ul>
 * 
 * <h3>Citation Format</h3>
 * <p>When document sources are available, the response text may include citations
 * in the format {@code [UUID:chunk-N]}, which can be matched to entries in the
 * {@code sources} array using their {@code id} and {@code chunkIndex} fields.</p>
 * 
 * <h3>Usage Example</h3>
 * <pre>
 * ChatResponse response = chatService.chat(request);
 * 
 * // Extract unique document IDs
 * List&lt;UUID&gt; documentIds = response.sources().stream()
 *     .map(SearchResult::id)
 *     .filter(Objects::nonNull)
 *     .distinct()
 *     .toList();
 * 
 * // Verify citations match sources
 * Pattern citationPattern = Pattern.compile("\\[([a-f0-9-]+):chunk-(\\d+)\\]");
 * Matcher matcher = citationPattern.matcher(response.response());
 * while (matcher.find()) {
 *     String docId = matcher.group(1);
 *     int chunkIdx = Integer.parseInt(matcher.group(2));
 *     // Match against sources array
 * }
 * </pre>
 * 
 * @param response AI-generated response text (may include citations)
 * @param messages Complete conversation history including current exchange
 * @param sources Source chunks used to generate response (includes document IDs and chunk indices)
 * @param model LLM model identifier used for generation
 * @param totalDuration Total processing time in milliseconds (nullable)
 * @param promptEvalCount Number of tokens in the prompt (nullable)
 * @param evalCount Number of tokens in the completion (nullable)
 * 
 * @see SearchResult
 * @see ChatService
 * @see <a href="/specs/003-chat-chunk-metadata/spec.md">Feature Specification</a>
 * @since 1.0
 */
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
