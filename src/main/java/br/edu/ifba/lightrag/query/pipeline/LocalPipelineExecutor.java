package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.query.KeywordExtractor;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pipeline-based executor for LOCAL mode queries.
 * 
 * <p>LOCAL mode focuses on context-dependent information using vector similarity search
 * on text chunks. When keyword extraction is enabled, low-level (entity) keywords are
 * used for more precise retrieval.</p>
 * 
 * <h2>Pipeline Configuration:</h2>
 * <ol>
 *   <li>ChunkSearchStage - Searches text chunks by vector similarity</li>
 *   <li>TruncateStage - Applies token budget (chunk-focused)</li>
 *   <li>MergeStage - Merges chunks (single source, no interleaving)</li>
 *   <li>ContextBuilderStage - Builds final prompt with citations</li>
 * </ol>
 * 
 * @since spec-008
 */
public class LocalPipelineExecutor extends PipelineQueryExecutor {
    
    /**
     * Creates a LocalPipelineExecutor without keyword extraction.
     */
    public LocalPipelineExecutor(
            @NotNull LLMFunction llmFunction,
            @NotNull EmbeddingFunction embeddingFunction,
            @NotNull KVStorage chunkStorage,
            @NotNull VectorStorage chunkVectorStorage,
            @NotNull VectorStorage entityVectorStorage,
            @NotNull GraphStorage graphStorage,
            @NotNull String systemPrompt) {
        this(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage,
                entityVectorStorage, graphStorage, systemPrompt, null, null);
    }
    
    /**
     * Creates a LocalPipelineExecutor with optional keyword extraction and config.
     */
    public LocalPipelineExecutor(
            @NotNull LLMFunction llmFunction,
            @NotNull EmbeddingFunction embeddingFunction,
            @NotNull KVStorage chunkStorage,
            @NotNull VectorStorage chunkVectorStorage,
            @NotNull VectorStorage entityVectorStorage,
            @NotNull GraphStorage graphStorage,
            @NotNull String systemPrompt,
            @Nullable KeywordExtractor keywordExtractor,
            @Nullable LightRAGExtractionConfig config) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage,
                entityVectorStorage, graphStorage, systemPrompt, keywordExtractor, config);
    }
    
    @Override
    protected QueryPipeline buildPipeline() {
        logger.debug("Building LOCAL pipeline");
        
        // LOCAL mode: chunk-focused search with citations
        return QueryPipeline.builder()
                // Stage 1: Search chunks by vector similarity
                .addStage(new ChunkSearchStage(chunkVectorStorage, embeddingFunction, keywordExtractor))
                // Stage 2: Apply token budget (mostly chunks)
                .addStage(new TruncateStage(getMaxTokens(), 0.9, 0.05, 0.05))
                // Stage 3: Merge (single source type, preserves order)
                .addStage(new MergeStage(getMaxTokens()))
                // Stage 4: Build context with headers and conversation history
                .addStage(new ContextBuilderStage())
                .llmFunction(llmFunction)
                .systemPrompt(systemPrompt)
                .build();
    }
}
