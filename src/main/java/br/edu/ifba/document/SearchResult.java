package br.edu.ifba.document;

import java.util.UUID;

/**
 * Represents a single document chunk used as context for AI response generation.
 * 
 * <p>This record contains metadata that enables source traceability and citation verification
 * in chat responses. The {@code id} field uniquely identifies the chunk, while {@code documentId}
 * identifies the source document. Together with {@code chunkIndex}, these fields allow users to
 * trace AI-generated responses back to specific document chunks.</p>
 * 
 * <h3>Metadata Fields</h3>
 * <ul>
 *   <li><b>id</b>: Chunk identifier (unique chunk ID) - null for synthesized answers</li>
 *   <li><b>documentId</b>: Document identifier (UUID) - null for synthesized answers</li>
 *   <li><b>chunkIndex</b>: Chunk position within document (0-based) - null for synthesized answers</li>
 *   <li><b>chunkText</b>: Text content of the chunk</li>
 *   <li><b>source</b>: Human-readable source label (e.g., "document.pdf", "LightRAG Answer")</li>
 *   <li><b>distance</b>: Vector similarity score (0.0-1.0) - null for synthesized answers</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * <pre>
 * // Document chunk with metadata
 * SearchResult docChunk = new SearchResult(
 *     "chunk_abc123",                                             // Chunk ID
 *     UUID.fromString("a1b2c3d4-5678-9abc-def0-123456789abc"),   // Document ID
 *     "Machine learning is a subset of AI...",
 *     5,
 *     "ai-research.pdf",
 *     0.85
 * );
 * 
 * // Synthesized answer (null IDs)
 * SearchResult synthesized = new SearchResult(
 *     null,
 *     null,
 *     "Based on the knowledge graph...",
 *     null,
 *     "LightRAG Answer",
 *     null
 * );
 * </pre>
 * 
 * @param id Chunk identifier (unique ID for this specific chunk) - null for synthesized answers
 * @param documentId Document identifier (UUID) - identifies the source document; null for synthesized answers
 * @param chunkText Text content of the chunk - always present
 * @param chunkIndex Chunk position within document (0-based index) - null for synthesized answers or non-chunked sources
 * @param source Human-readable source label - always present (e.g., filename or "LightRAG Answer")
 * @param distance Vector similarity score (0.0 = identical, 1.0 = dissimilar) - null for synthesized answers
 * 
 * @see br.edu.ifba.chat.ChatResponse
 * @see SearchService
 * @since 1.0
 */
public record SearchResult(
        String id,
        UUID documentId,
        String chunkText,
        Integer chunkIndex,
        String source,
        Double distance) {
}
