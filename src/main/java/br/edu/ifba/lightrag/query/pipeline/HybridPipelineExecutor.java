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
 * Pipeline-based executor for HYBRID mode queries.
 * 
 * <p>HYBRID mode combines both LOCAL (chunk-based) and GLOBAL (entity/relation-based)
 * retrieval strategies. It searches both text chunks and knowledge graph entities,
 * then merges results using round-robin interleaving for balanced context.</p>
 * 
 * <h2>Pipeline Configuration:</h2>
 * <ol>
 *   <li>ChunkSearchStage - Searches text chunks by vector similarity</li>
 *   <li>EntitySearchStage - Searches entities and their relations</li>
 *   <li>TruncateStage - Applies token budget (balanced across all types)</li>
 *   <li>MergeStage - Round-robin merge of chunks, entities, and relations</li>
 *   <li>ContextBuilderStage - Builds final prompt with citations and graph context</li>
 * </ol>
 * 
 * <h2>Token Budget Allocation:</h2>
 * <p>By default, uses configurable ratios from LightRAGExtractionConfig:</p>
 * <ul>
 *   <li>Chunks: 30% (context.chunkBudgetRatio)</li>
 *   <li>Entities: 40% (context.entityBudgetRatio)</li>
 *   <li>Relations: 30% (context.relationBudgetRatio)</li>
 * </ul>
 * 
 * @since spec-008
 */
public class HybridPipelineExecutor extends PipelineQueryExecutor {
    
    /**
     * Creates a HybridPipelineExecutor without keyword extraction.
     *
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param systemPrompt System prompt for LLM
     */
    public HybridPipelineExecutor(
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
     * Creates a HybridPipelineExecutor with optional keyword extraction and config.
     *
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param systemPrompt System prompt for LLM
     * @param keywordExtractor Optional keyword extractor for query enhancement
     * @param config Optional configuration (null for defaults)
     */
    public HybridPipelineExecutor(
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
        logger.debug("Building HYBRID pipeline");
        
        // HYBRID mode: combines chunk search with entity/relation search
        // Uses round-robin merging to balance context diversity
        return QueryPipeline.builder()
                // Stage 1: Search chunks by vector similarity (uses low-level keywords if available)
                .addStage(new ChunkSearchStage(chunkVectorStorage, embeddingFunction, keywordExtractor))
                // Stage 2: Search entities and fetch relations (uses high-level keywords if available)
                .addStage(new EntitySearchStage(entityVectorStorage, graphStorage, embeddingFunction, keywordExtractor, true))
                // Stage 3: Apply token budget (balanced across all sources)
                .addStage(new TruncateStage(getMaxTokens(), getChunkBudgetRatio(), getEntityBudgetRatio(), getRelationBudgetRatio()))
                // Stage 4: Round-robin merge with chunks prioritized for hybrid mode
                .addStage(new MergeStage(MergeStage.MergeOrder.CHUNK_ENTITY_RELATION))
                // Stage 5: Build context with headers, citations, and structured output
                .addStage(new ContextBuilderStage(true, true, null))
                .llmFunction(llmFunction)
                .systemPrompt(systemPrompt)
                .build();
    }
}
