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
 * Pipeline-based executor for GLOBAL mode queries.
 * 
 * <p>GLOBAL mode focuses on knowledge graph data - entities and relationships.
 * When keyword extraction is enabled, high-level (thematic) keywords are used
 * for relationship-focused retrieval.</p>
 * 
 * <h2>Pipeline Configuration:</h2>
 * <ol>
 *   <li>EntitySearchStage - Searches entities and their relations</li>
 *   <li>TruncateStage - Applies token budget (entity/relation focused)</li>
 *   <li>MergeStage - Merges entities and relations</li>
 *   <li>ContextBuilderStage - Builds final prompt with graph context</li>
 * </ol>
 * 
 * @since spec-008
 */
public class GlobalPipelineExecutor extends PipelineQueryExecutor {
    
    /**
     * Creates a GlobalPipelineExecutor without keyword extraction.
     */
    public GlobalPipelineExecutor(
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
     * Creates a GlobalPipelineExecutor with optional keyword extraction and config.
     */
    public GlobalPipelineExecutor(
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
        logger.debug("Building GLOBAL pipeline");
        
        // GLOBAL mode: entity/relation focused, no citations (graph doesn't have doc refs)
        return QueryPipeline.builder()
                // Stage 1: Search entities and fetch relations (includeRelations=true for GLOBAL mode)
                .addStage(new EntitySearchStage(entityVectorStorage, graphStorage, embeddingFunction, keywordExtractor, true))
                // Stage 2: Apply token budget (entity and relation focused)
                .addStage(new TruncateStage(getMaxTokens(), 0.1, getEntityBudgetRatio(), getRelationBudgetRatio()))
                // Stage 3: Merge entities and relations with round-robin
                .addStage(new MergeStage(MergeStage.MergeOrder.ENTITY_RELATION_CHUNK))
                // Stage 4: Build context (grouped by type - entities, then relations)
                .addStage(new ContextBuilderStage(true, true, null))
                .llmFunction(llmFunction)
                .systemPrompt(systemPrompt)
                .build();
    }
}
