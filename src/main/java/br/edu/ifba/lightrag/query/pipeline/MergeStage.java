package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.query.ContextItem;
import br.edu.ifba.lightrag.query.ContextMerger;
import br.edu.ifba.lightrag.query.MergeResult;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline stage that merges truncated context items using round-robin interleaving.
 * 
 * <p>This stage takes the truncated items from each source type (chunks, entities,
 * relations) and interleaves them to ensure diversity in the final context.</p>
 * 
 * <h2>Round-Robin Algorithm:</h2>
 * <pre>
 * Entities: [E1, E2, E3]
 * Relations: [R1, R2]
 * Chunks: [C1, C2, C3, C4]
 * 
 * Result: E1, R1, C1, E2, R2, C2, E3, C3, C4
 * </pre>
 * 
 * <h2>Merge Order Options:</h2>
 * <ul>
 *   <li>{@link MergeOrder#ENTITY_RELATION_CHUNK} - Default, prioritizes graph data</li>
 *   <li>{@link MergeOrder#CHUNK_ENTITY_RELATION} - Prioritizes raw text chunks</li>
 *   <li>{@link MergeOrder#RELATION_ENTITY_CHUNK} - Prioritizes relationship context</li>
 * </ul>
 * 
 * @since spec-008
 * @see ContextMerger
 */
public class MergeStage implements PipelineStage {
    
    private static final Logger logger = LoggerFactory.getLogger(MergeStage.class);
    private static final String STAGE_NAME = "merge";
    
    /**
     * Defines the order in which source types are interleaved during merge.
     */
    public enum MergeOrder {
        /** Entities first, then relations, then chunks */
        ENTITY_RELATION_CHUNK,
        /** Chunks first, then entities, then relations */
        CHUNK_ENTITY_RELATION,
        /** Relations first, then entities, then chunks */
        RELATION_ENTITY_CHUNK
    }
    
    private final ContextMerger contextMerger;
    private final MergeOrder mergeOrder;
    private final int maxTokens;
    
    /**
     * Creates a MergeStage with default settings.
     * 
     * <p>Uses ENTITY_RELATION_CHUNK order and 4000 token budget.</p>
     */
    public MergeStage() {
        this(new ContextMerger(), MergeOrder.ENTITY_RELATION_CHUNK, 4000);
    }
    
    /**
     * Creates a MergeStage with custom token budget.
     * 
     * @param maxTokens maximum tokens for merged context
     */
    public MergeStage(int maxTokens) {
        this(new ContextMerger(), MergeOrder.ENTITY_RELATION_CHUNK, maxTokens);
    }
    
    /**
     * Creates a MergeStage with custom merge order.
     * 
     * @param mergeOrder the order to interleave sources
     */
    public MergeStage(@NotNull MergeOrder mergeOrder) {
        this(new ContextMerger(), mergeOrder, 4000);
    }
    
    /**
     * Creates a MergeStage with full customization.
     * 
     * @param contextMerger the merger to use
     * @param mergeOrder the order to interleave sources
     * @param maxTokens maximum tokens for merged context
     */
    public MergeStage(@NotNull ContextMerger contextMerger, 
                      @NotNull MergeOrder mergeOrder, 
                      int maxTokens) {
        this.contextMerger = contextMerger;
        this.mergeOrder = mergeOrder;
        this.maxTokens = maxTokens;
    }
    
    @Override
    public CompletableFuture<PipelineContext> process(@NotNull PipelineContext context) {
        logger.debug("Starting merge with order={}, maxTokens={}", mergeOrder, maxTokens);
        
        // Get truncated items from context
        List<ContextItem> entities = context.getTruncatedEntities();
        List<ContextItem> relations = context.getTruncatedRelations();
        List<ContextItem> chunks = context.getTruncatedChunks();
        
        logger.debug("Merging {} entities, {} relations, {} chunks", 
                entities.size(), relations.size(), chunks.size());
        
        // Build source list in the configured order
        List<List<ContextItem>> sources = buildSourceList(entities, relations, chunks);
        
        // Perform round-robin merge
        MergeResult result = contextMerger.mergeWithMetadata(sources, maxTokens);
        
        // Store merged items in context
        context.setMergedItems(result.includedItems());
        
        // Update context with merged string for convenience
        context.setFinalContext(result.mergedContext());
        
        logger.debug("Merge complete: {} items included, {} truncated, {} tokens used",
                result.itemsIncluded(), result.itemsTruncated(), result.totalTokens());
        
        return CompletableFuture.completedFuture(context);
    }
    
    /**
     * Builds the source list in the configured merge order.
     */
    private List<List<ContextItem>> buildSourceList(
            List<ContextItem> entities,
            List<ContextItem> relations,
            List<ContextItem> chunks) {
        
        List<List<ContextItem>> sources = new ArrayList<>(3);
        
        switch (mergeOrder) {
            case ENTITY_RELATION_CHUNK:
                sources.add(entities);
                sources.add(relations);
                sources.add(chunks);
                break;
            case CHUNK_ENTITY_RELATION:
                sources.add(chunks);
                sources.add(entities);
                sources.add(relations);
                break;
            case RELATION_ENTITY_CHUNK:
                sources.add(relations);
                sources.add(entities);
                sources.add(chunks);
                break;
        }
        
        return sources;
    }
    
    @Override
    public String getName() {
        return STAGE_NAME;
    }
    
    @Override
    public boolean shouldSkip(@NotNull PipelineContext context) {
        // Skip if no truncated items exist
        return context.getTruncatedChunks().isEmpty() &&
               context.getTruncatedEntities().isEmpty() &&
               context.getTruncatedRelations().isEmpty();
    }
    
    /**
     * Gets the configured merge order.
     * 
     * @return the merge order
     */
    public MergeOrder getMergeOrder() {
        return mergeOrder;
    }
    
    /**
     * Gets the configured max tokens.
     * 
     * @return the max tokens budget
     */
    public int getMaxTokens() {
        return maxTokens;
    }
}
