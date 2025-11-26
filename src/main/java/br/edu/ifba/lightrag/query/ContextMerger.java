package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.utils.TokenUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Context merger that uses round-robin interleaving for diverse results.
 * 
 * <p>Instead of simply concatenating all results from each source,
 * this merger interleaves items from different sources (entity, relation, chunk)
 * to ensure diversity in the final context.</p>
 * 
 * <h2>Round-Robin Algorithm:</h2>
 * <p>Given multiple lists of context items, take one item from each list
 * in rotation until the token budget is exhausted or all items are consumed.</p>
 * 
 * <h2>Example:</h2>
 * <pre>{@code
 * Entities: [E1, E2, E3]
 * Relations: [R1, R2]
 * Chunks: [C1, C2, C3, C4]
 * 
 * Round-robin order: E1, R1, C1, E2, R2, C2, E3, C3, C4
 * }</pre>
 * 
 * @see ContextItem
 * @see MergeResult
 */
public class ContextMerger {
    
    private static final Logger logger = LoggerFactory.getLogger(ContextMerger.class);
    
    /** Default separator between context items */
    private static final String DEFAULT_SEPARATOR = "\n\n";
    
    /**
     * Merges multiple context sources using round-robin interleaving.
     * 
     * <p>Simple version that returns just the merged string.</p>
     * 
     * @param sources list of source lists to merge
     * @param maxTokens maximum token budget
     * @return merged context string
     */
    public String merge(@NotNull List<List<ContextItem>> sources, int maxTokens) {
        return mergeWithMetadata(sources, maxTokens).mergedContext();
    }
    
    /**
     * Merges multiple context sources with full metadata tracking.
     * 
     * <p>Uses round-robin interleaving to ensure diversity across sources.</p>
     * 
     * @param sources list of source lists to merge
     * @param maxTokens maximum token budget
     * @return MergeResult with merged content and metadata
     */
    public MergeResult mergeWithMetadata(@NotNull List<List<ContextItem>> sources, int maxTokens) {
        if (sources.isEmpty() || maxTokens <= 0) {
            return MergeResult.empty();
        }
        
        // Track state for round-robin
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            indices.add(0);
        }
        
        List<ContextItem> included = new ArrayList<>();
        StringBuilder merged = new StringBuilder();
        int totalTokens = 0;
        int totalItems = sources.stream().mapToInt(List::size).sum();
        
        // Round-robin: take one item from each non-exhausted source
        boolean anyRemaining = true;
        while (anyRemaining && totalTokens < maxTokens) {
            anyRemaining = false;
            
            for (int sourceIdx = 0; sourceIdx < sources.size(); sourceIdx++) {
                List<ContextItem> source = sources.get(sourceIdx);
                int itemIdx = indices.get(sourceIdx);
                
                // Skip exhausted sources
                if (itemIdx >= source.size()) {
                    continue;
                }
                
                anyRemaining = true;
                ContextItem item = source.get(itemIdx);
                
                // Check if adding this item exceeds budget
                int itemTokens = item.tokens();
                if (itemTokens <= 0) {
                    // Estimate tokens if not pre-calculated
                    itemTokens = TokenUtil.estimateTokens(item.content());
                }
                
                // Account for separator tokens
                int separatorTokens = merged.isEmpty() ? 0 : 
                    TokenUtil.estimateTokens(DEFAULT_SEPARATOR);
                
                if (totalTokens + itemTokens + separatorTokens > maxTokens) {
                    // Budget exceeded, skip this item but continue checking others
                    indices.set(sourceIdx, itemIdx + 1);
                    continue;
                }
                
                // Add separator if not first item
                if (!merged.isEmpty()) {
                    merged.append(DEFAULT_SEPARATOR);
                    totalTokens += separatorTokens;
                }
                
                // Add content
                merged.append(item.content());
                totalTokens += itemTokens;
                included.add(item);
                
                // Move to next item in this source
                indices.set(sourceIdx, itemIdx + 1);
            }
        }
        
        int itemsIncluded = included.size();
        int itemsTruncated = totalItems - itemsIncluded;
        
        logger.debug("Merged {} items ({} truncated) using {} tokens out of {} budget", 
            itemsIncluded, itemsTruncated, totalTokens, maxTokens);
        
        return new MergeResult(
            merged.toString(),
            included,
            totalTokens,
            itemsIncluded,
            itemsTruncated
        );
    }
    
    /**
     * Merges items from two sources (entity/relation or local/global).
     * 
     * <p>Convenience method for the common two-source case.</p>
     * 
     * @param source1 first source list
     * @param source2 second source list
     * @param maxTokens maximum token budget
     * @return merged context string
     */
    public String mergeTwoSources(@NotNull List<ContextItem> source1, 
                                   @NotNull List<ContextItem> source2, 
                                   int maxTokens) {
        return merge(List.of(source1, source2), maxTokens);
    }
    
    /**
     * Merges items from three sources (entity/relation/chunk).
     * 
     * <p>Convenience method for the common three-source case.</p>
     * 
     * @param entities entity context items
     * @param relations relation context items
     * @param chunks chunk context items
     * @param maxTokens maximum token budget
     * @return merged context string
     */
    public String mergeThreeSources(@NotNull List<ContextItem> entities,
                                     @NotNull List<ContextItem> relations,
                                     @NotNull List<ContextItem> chunks,
                                     int maxTokens) {
        return merge(List.of(entities, relations, chunks), maxTokens);
    }
    
    /**
     * Merges a single list of items (no interleaving needed).
     * 
     * @param items items to merge
     * @param maxTokens maximum token budget
     * @return merged context string
     */
    public String mergeSingleSource(@NotNull List<ContextItem> items, int maxTokens) {
        return merge(List.of(items), maxTokens);
    }
    
    /**
     * Estimates how many items can fit within a token budget.
     * 
     * <p>Useful for pre-filtering or limiting database queries.</p>
     * 
     * @param items candidate items
     * @param maxTokens token budget
     * @return estimated number of items that fit
     */
    public int estimateCapacity(@NotNull List<ContextItem> items, int maxTokens) {
        int count = 0;
        int tokens = 0;
        
        for (ContextItem item : items) {
            int itemTokens = item.tokens();
            if (itemTokens <= 0) {
                itemTokens = TokenUtil.estimateTokens(item.content());
            }
            
            int separatorTokens = count == 0 ? 0 : TokenUtil.estimateTokens(DEFAULT_SEPARATOR);
            
            if (tokens + itemTokens + separatorTokens > maxTokens) {
                break;
            }
            
            tokens += itemTokens + separatorTokens;
            count++;
        }
        
        return count;
    }
}
