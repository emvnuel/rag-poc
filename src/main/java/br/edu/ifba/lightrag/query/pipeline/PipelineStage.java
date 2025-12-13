package br.edu.ifba.lightrag.query.pipeline;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for a stage in the query pipeline.
 * 
 * <p>Each stage processes the pipeline context and passes it to the next stage.
 * Stages should be stateless and thread-safe, operating only on the context.</p>
 * 
 * <h2>Stage Contract:</h2>
 * <ul>
 *   <li>Read inputs from PipelineContext</li>
 *   <li>Perform stage-specific processing</li>
 *   <li>Write outputs to PipelineContext</li>
 *   <li>Return the (possibly modified) context</li>
 * </ul>
 * 
 * <h2>Example Implementation:</h2>
 * <pre>{@code
 * public class MyStage implements PipelineStage {
 *     @Override
 *     public CompletableFuture<PipelineContext> process(PipelineContext context) {
 *         // Read from context
 *         List<SourceChunk> chunks = context.getChunkCandidates();
 *         
 *         // Process
 *         List<SourceChunk> filtered = doSomething(chunks);
 *         
 *         // Write to context
 *         context.setChunkCandidates(filtered);
 *         
 *         return CompletableFuture.completedFuture(context);
 *     }
 *     
 *     @Override
 *     public String getName() {
 *         return "my-stage";
 *     }
 * }
 * }</pre>
 * 
 * @since spec-008
 */
public interface PipelineStage {
    
    /**
     * Processes the pipeline context.
     * 
     * <p>Implementations should:
     * <ol>
     *   <li>Read required inputs from the context</li>
     *   <li>Perform their specific processing</li>
     *   <li>Write outputs back to the context</li>
     *   <li>Return the context (same instance, possibly modified)</li>
     * </ol>
     * 
     * @param context The pipeline context containing all data
     * @return CompletableFuture that completes with the processed context
     */
    CompletableFuture<PipelineContext> process(@NotNull PipelineContext context);
    
    /**
     * Returns the name of this stage for logging and debugging.
     * 
     * @return Stage name (e.g., "search", "truncate", "merge", "build-context")
     */
    String getName();
    
    /**
     * Checks if this stage should be skipped for the given context.
     * 
     * <p>Default implementation always returns false (stage is never skipped).
     * Override to implement conditional stage execution.</p>
     * 
     * @param context The pipeline context
     * @return true if stage should be skipped, false otherwise
     */
    default boolean shouldSkip(@NotNull PipelineContext context) {
        return false;
    }
}
