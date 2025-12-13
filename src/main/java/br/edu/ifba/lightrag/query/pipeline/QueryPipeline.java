package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.llm.LLMFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates query execution through a pipeline of stages.
 * 
 * <p>The pipeline processes queries through four stages:</p>
 * <ol>
 *   <li><b>Search</b> - Retrieves candidates (chunks, entities, relations)</li>
 *   <li><b>Truncate</b> - Applies token budgets per source type</li>
 *   <li><b>Merge</b> - Combines sources using round-robin interleaving</li>
 *   <li><b>BuildContext</b> - Formats final context with headers and citations</li>
 * </ol>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * QueryPipeline pipeline = QueryPipeline.builder()
 *     .addStage(new ChunkSearchStage(vectorStorage, embeddingFunction))
 *     .addStage(new TruncateStage(config))
 *     .addStage(new MergeStage(contextMerger))
 *     .addStage(new ContextBuilderStage())
 *     .llmFunction(llmFunction)
 *     .systemPrompt(systemPrompt)
 *     .build();
 * 
 * LightRAGQueryResult result = pipeline.execute(query, param).join();
 * }</pre>
 * 
 * @since spec-008
 */
public class QueryPipeline {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryPipeline.class);
    
    private final List<PipelineStage> stages;
    private final LLMFunction llmFunction;
    private final String systemPrompt;
    
    private QueryPipeline(Builder builder) {
        this.stages = new ArrayList<>(builder.stages);
        this.llmFunction = builder.llmFunction;
        this.systemPrompt = builder.systemPrompt;
    }
    
    /**
     * Executes the query through all pipeline stages.
     * 
     * @param query The user query
     * @param param Query parameters
     * @param queryEmbedding Pre-computed query embedding (can be null)
     * @return CompletableFuture with the query result
     */
    public CompletableFuture<LightRAGQueryResult> execute(
            @NotNull String query,
            @NotNull QueryParam param,
            @Nullable Object queryEmbedding) {
        
        logger.info("Starting pipeline execution for mode: {}", param.getMode());
        long startTime = System.currentTimeMillis();
        
        // Create initial context
        PipelineContext context = new PipelineContext(query, param, queryEmbedding);
        
        // Execute all stages sequentially
        CompletableFuture<PipelineContext> future = CompletableFuture.completedFuture(context);
        
        for (PipelineStage stage : stages) {
            future = future.thenCompose(ctx -> executeStage(stage, ctx));
        }
        
        // After all stages, generate final response
        return future.thenCompose(ctx -> generateResponse(ctx, startTime));
    }
    
    /**
     * Executes the query through all pipeline stages (embedding computed internally).
     */
    public CompletableFuture<LightRAGQueryResult> execute(
            @NotNull String query,
            @NotNull QueryParam param) {
        return execute(query, param, null);
    }
    
    /**
     * Executes a single stage with logging and skip detection.
     */
    private CompletableFuture<PipelineContext> executeStage(
            @NotNull PipelineStage stage,
            @NotNull PipelineContext context) {
        
        // Check if stage should be skipped
        if (stage.shouldSkip(context)) {
            logger.debug("Skipping stage: {}", stage.getName());
            return CompletableFuture.completedFuture(context);
        }
        
        logger.debug("Executing stage: {}", stage.getName());
        long stageStart = System.currentTimeMillis();
        
        return stage.process(context)
                .thenApply(ctx -> {
                    long elapsed = System.currentTimeMillis() - stageStart;
                    logger.debug("Stage {} completed in {}ms", stage.getName(), elapsed);
                    return ctx;
                })
                .exceptionally(e -> {
                    logger.error("Stage {} failed: {}", stage.getName(), e.getMessage(), e);
                    throw new PipelineException("Stage " + stage.getName() + " failed", e);
                });
    }
    
    /**
     * Generates the final response after all stages complete.
     */
    private CompletableFuture<LightRAGQueryResult> generateResponse(
            @NotNull PipelineContext context,
            long startTime) {
        
        QueryParam param = context.getParam();
        
        // If only context is needed, return early
        if (param.isOnlyNeedContext()) {
            logger.debug("Returning context only (no LLM call)");
            return CompletableFuture.completedFuture(buildResult(context, context.getFinalContext()));
        }
        
        // If only prompt is needed, return early
        if (param.isOnlyNeedPrompt()) {
            logger.debug("Returning prompt only (no LLM call)");
            return CompletableFuture.completedFuture(buildResult(context, context.getFinalPrompt()));
        }
        
        // Call LLM to generate final answer
        logger.debug("Calling LLM with context ({} tokens)", context.getTotalTokens());
        
        return llmFunction.apply(context.getFinalPrompt(), systemPrompt)
                .thenApply(answer -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    logger.info("Pipeline completed in {}ms, mode={}, sources={}", 
                            elapsed, param.getMode(), context.getAllSources().size());
                    return buildResult(context, answer);
                });
    }
    
    /**
     * Builds the final query result from context.
     */
    private LightRAGQueryResult buildResult(
            @NotNull PipelineContext context,
            @NotNull String answer) {
        return new LightRAGQueryResult(
                answer,
                context.getAllSources(),
                context.getParam().getMode(),
                context.getAllSources().size()
        );
    }
    
    /**
     * Creates a new builder for QueryPipeline.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for QueryPipeline.
     */
    public static class Builder {
        private final List<PipelineStage> stages = new ArrayList<>();
        private LLMFunction llmFunction;
        private String systemPrompt = "";
        
        /**
         * Adds a stage to the pipeline.
         * Stages are executed in the order they are added.
         */
        public Builder addStage(@NotNull PipelineStage stage) {
            this.stages.add(stage);
            return this;
        }
        
        /**
         * Adds multiple stages to the pipeline.
         */
        public Builder addStages(@NotNull List<PipelineStage> stages) {
            this.stages.addAll(stages);
            return this;
        }
        
        /**
         * Sets the LLM function for generating final responses.
         */
        public Builder llmFunction(@NotNull LLMFunction llmFunction) {
            this.llmFunction = llmFunction;
            return this;
        }
        
        /**
         * Sets the system prompt for LLM calls.
         */
        public Builder systemPrompt(@NotNull String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }
        
        /**
         * Builds the QueryPipeline.
         * 
         * @throws IllegalStateException if required components are missing
         */
        public QueryPipeline build() {
            if (llmFunction == null) {
                throw new IllegalStateException("llmFunction is required");
            }
            if (stages.isEmpty()) {
                throw new IllegalStateException("At least one stage is required");
            }
            return new QueryPipeline(this);
        }
    }
    
    /**
     * Exception thrown when a pipeline stage fails.
     */
    public static class PipelineException extends RuntimeException {
        public PipelineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
