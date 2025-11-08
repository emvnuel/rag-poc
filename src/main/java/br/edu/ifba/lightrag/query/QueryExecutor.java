package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Executes a query and returns the result.
     *
     * @param query The user's query string
     * @param param Query parameters
     * @return CompletableFuture with the query response
     */
    public abstract CompletableFuture<String> execute(
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
}
