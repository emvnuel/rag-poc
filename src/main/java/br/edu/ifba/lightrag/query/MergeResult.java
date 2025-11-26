package br.edu.ifba.lightrag.query;

import java.util.List;

/**
 * Result of context merging operation.
 * 
 * <p>Contains the merged context string along with metadata about
 * what was included and what was truncated due to token limits.</p>
 * 
 * @param mergedContext the final merged context string
 * @param includedItems list of context items that were included
 * @param totalTokens total token count of the merged context
 * @param itemsIncluded number of items that fit within the budget
 * @param itemsTruncated number of items that were cut off due to budget
 */
public record MergeResult(
    String mergedContext,
    List<ContextItem> includedItems,
    int totalTokens,
    int itemsIncluded,
    int itemsTruncated
) {
    /**
     * Creates an empty merge result.
     * 
     * @return MergeResult with no content
     */
    public static MergeResult empty() {
        return new MergeResult("", List.of(), 0, 0, 0);
    }
    
    /**
     * Checks if the merge result has any content.
     * 
     * @return true if mergedContext is non-empty
     */
    public boolean hasContent() {
        return mergedContext != null && !mergedContext.isEmpty();
    }
    
    /**
     * Checks if any items were truncated.
     * 
     * @return true if itemsTruncated > 0
     */
    public boolean wasTruncated() {
        return itemsTruncated > 0;
    }
    
    /**
     * Gets utilization ratio of the token budget.
     * 
     * @param maxTokens the maximum token budget
     * @return ratio of used tokens to max tokens (0.0 to 1.0)
     */
    public double utilizationRatio(int maxTokens) {
        if (maxTokens <= 0) return 0.0;
        return Math.min(1.0, (double) totalTokens / maxTokens);
    }
}
