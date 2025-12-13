package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.embedding.EmbeddingFunction;
import br.edu.ifba.lightrag.llm.LLMFunction;
import br.edu.ifba.lightrag.query.ContextMerger;
import br.edu.ifba.lightrag.query.KeywordExtractor;
import br.edu.ifba.lightrag.query.QueryExecutor;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.KVStorage;
import br.edu.ifba.lightrag.storage.VectorStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Base class for pipeline-based query executors.
 * 
 * <p>Provides common infrastructure for building query pipelines with configurable stages.
 * Subclasses implement {@link #buildPipeline()} to define their specific stage configuration.</p>
 * 
 * <h2>Pipeline Architecture:</h2>
 * <pre>
 * Query → [Search] → [Truncate] → [Merge] → [BuildContext] → LLM → Response
 * </pre>
 * 
 * <h2>Subclass Usage:</h2>
 * <pre>{@code
 * public class LocalPipelineExecutor extends PipelineQueryExecutor {
 *     @Override
 *     protected QueryPipeline buildPipeline() {
 *         return QueryPipeline.builder()
 *             .addStage(new ChunkSearchStage(chunkVectorStorage, embeddingFunction))
 *             .addStage(new TruncateStage(config))
 *             .addStage(new MergeStage())
 *             .addStage(new ContextBuilderStage())
 *             .llmFunction(llmFunction)
 *             .systemPrompt(systemPrompt)
 *             .build();
 *     }
 * }
 * }</pre>
 * 
 * @since spec-008
 */
public abstract class PipelineQueryExecutor extends QueryExecutor {
    
    protected final String systemPrompt;
    protected final KeywordExtractor keywordExtractor;
    protected final LightRAGExtractionConfig config;
    protected final ContextMerger contextMerger;
    
    /** Cached pipeline instance (built lazily) */
    private QueryPipeline pipeline;
    
    /**
     * Creates a PipelineQueryExecutor with all dependencies.
     * 
     * @param llmFunction LLM function for generating responses
     * @param embeddingFunction Embedding function for vector search
     * @param chunkStorage KV storage for chunks
     * @param chunkVectorStorage Vector storage for chunk embeddings
     * @param entityVectorStorage Vector storage for entity embeddings
     * @param graphStorage Graph storage for relationships
     * @param systemPrompt System prompt for LLM
     * @param keywordExtractor Optional keyword extractor (null to disable)
     * @param config Optional configuration (null for defaults)
     */
    protected PipelineQueryExecutor(
            @NotNull LLMFunction llmFunction,
            @NotNull EmbeddingFunction embeddingFunction,
            @NotNull KVStorage chunkStorage,
            @NotNull VectorStorage chunkVectorStorage,
            @NotNull VectorStorage entityVectorStorage,
            @NotNull GraphStorage graphStorage,
            @NotNull String systemPrompt,
            @Nullable KeywordExtractor keywordExtractor,
            @Nullable LightRAGExtractionConfig config) {
        super(llmFunction, embeddingFunction, chunkStorage, chunkVectorStorage, entityVectorStorage, graphStorage);
        this.systemPrompt = systemPrompt;
        this.keywordExtractor = keywordExtractor;
        this.config = config;
        this.contextMerger = new ContextMerger();
    }
    
    /**
     * Builds the query pipeline with appropriate stages.
     * 
     * <p>Subclasses must implement this method to define their pipeline configuration.
     * The pipeline is built once and cached for subsequent queries.</p>
     * 
     * @return configured QueryPipeline
     */
    protected abstract QueryPipeline buildPipeline();
    
    /**
     * Gets the pipeline, building it if necessary.
     */
    protected QueryPipeline getPipeline() {
        if (pipeline == null) {
            pipeline = buildPipeline();
        }
        return pipeline;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(
            @NotNull String query,
            @NotNull QueryParam param) {
        
        logger.info("Executing {} query via pipeline", param.getMode());
        
        // Compute query embedding first
        return embeddingFunction.embedSingle(query)
                .thenCompose(queryEmbedding -> {
                    // Execute pipeline with pre-computed embedding
                    return getPipeline().execute(query, param, queryEmbedding);
                });
    }
    
    /**
     * Gets the system prompt.
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }
    
    /**
     * Gets the keyword extractor.
     */
    @Nullable
    public KeywordExtractor getKeywordExtractor() {
        return keywordExtractor;
    }
    
    /**
     * Gets the extraction config.
     */
    @Nullable
    public LightRAGExtractionConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the default max tokens from config or returns default.
     */
    protected int getMaxTokens() {
        return config != null 
                ? config.query().context().maxTokens() 
                : 4000;
    }
    
    /**
     * Gets the chunk budget ratio from config or returns default.
     */
    protected double getChunkBudgetRatio() {
        return config != null 
                ? config.query().context().chunkBudgetRatio() 
                : 0.3;
    }
    
    /**
     * Gets the entity budget ratio from config or returns default.
     */
    protected double getEntityBudgetRatio() {
        return config != null 
                ? config.query().context().entityBudgetRatio() 
                : 0.4;
    }
    
    /**
     * Gets the relation budget ratio from config or returns default.
     */
    protected double getRelationBudgetRatio() {
        return config != null 
                ? config.query().context().relationBudgetRatio() 
                : 0.3;
    }
}
