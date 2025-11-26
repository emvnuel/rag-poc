package br.edu.ifba.lightrag.deletion;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for document deletion with intelligent knowledge graph rebuild.
 * 
 * <p>Contract:
 * <ul>
 *   <li>MUST identify entities/relations sourced from deleted document's chunks</li>
 *   <li>MUST completely remove entities with no remaining sources</li>
 *   <li>MUST rebuild descriptions for entities with partial sources</li>
 *   <li>MUST use cached extraction results (no LLM re-calls for cached content)</li>
 *   <li>MUST clean up vector embeddings</li>
 *   <li>MUST be transactional (all-or-nothing for critical operations)</li>
 * </ul>
 * 
 * <p>The deletion flow:
 * <ol>
 *   <li>Identify all chunks belonging to the document</li>
 *   <li>Find entities/relations that reference these chunks in their sourceIds</li>
 *   <li>Classify entities: fully delete (no remaining sources) or rebuild (partial sources)</li>
 *   <li>For rebuild: use cached extractions to regenerate descriptions</li>
 *   <li>Clean up vector embeddings for deleted entities and chunks</li>
 *   <li>Delete the document record</li>
 * </ol>
 * 
 * @since spec-007
 */
public interface DocumentDeletionService {
    
    /**
     * Deletes a document and intelligently rebuilds affected knowledge graph components.
     * 
     * <p>This method handles the complete deletion process:
     * <ul>
     *   <li>Entities sourced only from this document are fully deleted</li>
     *   <li>Entities sourced from multiple documents have their descriptions rebuilt</li>
     *   <li>Relations follow the same pattern as entities</li>
     *   <li>Vector embeddings are cleaned up</li>
     *   <li>Extraction cache entries are preserved (for audit/debug)</li>
     * </ul>
     *
     * @param projectId Project containing the document (required)
     * @param documentId Document to delete (required)
     * @param skipRebuild If true, skip KG rebuild (faster, may leave stale entities)
     * @return CompletableFuture with detailed result of deletion and rebuild operations
     * @throws IllegalArgumentException if document not found in project
     * @throws IllegalStateException if deletion cannot proceed due to system state
     */
    CompletableFuture<KnowledgeRebuildResult> deleteDocument(
        @NotNull UUID projectId,
        @NotNull UUID documentId,
        boolean skipRebuild
    );
    
    /**
     * Deletes a document with full knowledge graph rebuild.
     * 
     * <p>Convenience method that calls {@link #deleteDocument(UUID, UUID, boolean)} 
     * with skipRebuild=false.
     *
     * @param projectId Project containing the document (required)
     * @param documentId Document to delete (required)
     * @return CompletableFuture with detailed result of deletion and rebuild operations
     * @throws IllegalArgumentException if document not found in project
     */
    default CompletableFuture<KnowledgeRebuildResult> deleteDocument(
        @NotNull UUID projectId,
        @NotNull UUID documentId
    ) {
        return deleteDocument(projectId, documentId, false);
    }
}
