package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for query executors.
 * Each query mode (LOCAL, GLOBAL, HYBRID, etc.) has its own executor implementation.
 */
public abstract class QueryExecutor {
    
    protected static final Logger logger = LoggerFactory.getLogger(QueryExecutor.class);
    
    protected final LLMFunction llmFunction;
    protected final EmbeddingFunction embeddingFunction;
    protected final KVStorage chunkStorage;
    protected final VectorStorage chunkVectorStorage;
    protected final VectorStorage entityVectorStorage;
    protected final GraphStorage graphStorage;
    
    protected QueryExecutor(
        @NotNull LLMFunction llmFunction,
        @NotNull EmbeddingFunction embeddingFunction,
        @NotNull KVStorage chunkStorage,
        @NotNull VectorStorage chunkVectorStorage,
        @NotNull VectorStorage entityVectorStorage,
        @NotNull GraphStorage graphStorage
    ) {
        this.llmFunction = llmFunction;
        this.embeddingFunction = embeddingFunction;
        this.chunkStorage = chunkStorage;
        this.chunkVectorStorage = chunkVectorStorage;
        this.entityVectorStorage = entityVectorStorage;
        this.graphStorage = graphStorage;
    }
    
    /**
     * Executes a query and returns the result with source chunks.
     *
     * @param query The user's query string
     * @param param Query parameters
     * @return CompletableFuture with the query result including answer and sources
     */
    public abstract CompletableFuture<LightRAGQueryResult> execute(
        @NotNull String query,
        @NotNull QueryParam param
    );
    
    /**
     * Builds a prompt with context for the LLM.
     *
     * @param query The user's query
     * @param context Retrieved context from various sources
     * @param param Query parameters
     * @return Formatted prompt
     */
    protected String buildPrompt(
        @NotNull String query,
        @NotNull String context,
        @NotNull QueryParam param
    ) {
        StringBuilder prompt = new StringBuilder();
        
        // Add conversation history if available
        for (QueryParam.ConversationMessage msg : param.getConversationHistory()) {
            prompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        
        // Add context
        if (!context.isEmpty()) {
            prompt.append("\n---\n");
            prompt.append("Context:\n");
            prompt.append(context);
            prompt.append("\n---\n\n");
        }
        
        // Add query
        prompt.append("User: ").append(query).append("\n");
        prompt.append("Assistant: ");
        
        return prompt.toString();
    }
    
    /**
     * Formats context from retrieved chunks.
     *
     * @param chunks List of retrieved text chunks
     * @return Formatted context string
     */
    protected String formatChunkContext(@NotNull java.util.List<String> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("[").append(i + 1).append("] ").append(chunks.get(i)).append("\n\n");
        }
        
        return context.toString();
    }
    
    /**
     * Formats context from vector search results with UUID citations.
     * This allows the LLM to reference sources using document UUIDs like [a1b2c3d4-...].
     * Only includes citations for chunks with valid documentId.
     *
     * @param results List of vector search results
     * @return Formatted context string with UUID citations
     */
    protected String formatChunkContextWithCitations(@NotNull List<VectorStorage.VectorSearchResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        for (VectorStorage.VectorSearchResult result : results) {
            VectorStorage.VectorMetadata metadata = result.metadata();
            String content = metadata.content();
            String documentId = metadata.documentId();
            
            // Only include citation if documentId is available
            if (content != null && !content.isEmpty() && documentId != null) {
                context.append("[").append(documentId).append("] ").append(content).append("\n\n");
            } else if (content != null && !content.isEmpty()) {
                // Include content without citation if no documentId
                context.append(content).append("\n\n");
            }
        }
        
        return context.toString();
    }
    
    /**
     * Formats context from vector search results WITHOUT citations.
     * Used for GLOBAL mode where entities don't have direct document references.
     *
     * @param results List of vector search results
     * @return Formatted context string without citations
     */
    protected String formatContextWithoutCitations(@NotNull List<VectorStorage.VectorSearchResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        for (VectorStorage.VectorSearchResult result : results) {
            String content = result.metadata().content();
            if (content != null && !content.isEmpty()) {
                context.append(content).append("\n\n");
            }
        }
        
        return context.toString();
    }
    
    /**
     * Converts vector search results to source chunks for the query result.
     *
     * @param results List of vector search results
     * @return List of source chunks with metadata
     */
    protected List<SourceChunk> convertToSourceChunks(@NotNull List<VectorStorage.VectorSearchResult> results) {
        List<SourceChunk> sourceChunks = new ArrayList<>();
        
        for (VectorStorage.VectorSearchResult result : results) {
            VectorStorage.VectorMetadata metadata = result.metadata();
            sourceChunks.add(new SourceChunk(
                result.id(),                          // chunkId
                metadata.content(),                   // content
                result.score(),                       // relevanceScore
                metadata.documentId(),                // documentId (UUID from document table)
                metadata.documentId(),                // sourceId (same as documentId)
                metadata.chunkIndex(),                // chunkIndex
                metadata.type()                       // type (e.g., "chunk", "entity")
            ));
        }
        
        return sourceChunks;
    }
}
