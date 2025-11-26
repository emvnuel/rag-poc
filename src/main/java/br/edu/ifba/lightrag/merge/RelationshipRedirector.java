package br.edu.ifba.lightrag.merge;

import br.edu.ifba.lightrag.core.Relation;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Helper class for redirecting relationships during entity merge operations.
 * 
 * <p>Handles the complex logic of:
 * <ul>
 *   <li>Redirecting source/target entity references to the merged target entity</li>
 *   <li>Filtering out self-loop relationships (where source == target after redirect)</li>
 *   <li>Deduplicating relationships with the same source→target pair</li>
 *   <li>Merging relationship descriptions and weights when deduplicating</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * RelationshipRedirector redirector = new RelationshipRedirector();
 * 
 * // Redirect all relations from source entities to target
 * RelationshipRedirector.RedirectResult result = redirector.redirectAndDeduplicate(
 *     allRelations,
 *     Set.of("AI", "Artificial Intelligence"),
 *     "Artificial Intelligence",
 *     MergeStrategy.CONCATENATE
 * );
 * 
 * // Get processed relations and stats
 * List<Relation> redirected = result.redirectedRelations();
 * int selfLoopsRemoved = result.selfLoopsFiltered();
 * int duplicatesMerged = result.duplicatesMerged();
 * }</pre>
 * 
 * @since spec-007
 */
public final class RelationshipRedirector {
    
    private static final Logger LOG = Logger.getLogger(RelationshipRedirector.class);
    
    /**
     * Result of relationship redirection operation.
     * 
     * @param redirectedRelations Relations after redirection and deduplication
     * @param selfLoopsFiltered Number of self-loop relations that were filtered out
     * @param duplicatesMerged Number of duplicate relations that were merged
     * @param totalProcessed Total number of relations processed
     */
    public record RedirectResult(
        @NotNull List<Relation> redirectedRelations,
        int selfLoopsFiltered,
        int duplicatesMerged,
        int totalProcessed
    ) {
        public RedirectResult {
            Objects.requireNonNull(redirectedRelations, "redirectedRelations must not be null");
        }
        
        /**
         * Returns true if any modifications were made.
         */
        public boolean hasChanges() {
            return selfLoopsFiltered > 0 || duplicatesMerged > 0;
        }
    }
    
    /**
     * Redirects relationships from source entities to the target entity,
     * filters self-loops, and deduplicates.
     * 
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Redirects all relations where src or tgt matches a source entity</li>
     *   <li>Filters out self-loops (where src == tgt after redirect)</li>
     *   <li>Groups relations by src→tgt pair</li>
     *   <li>Merges duplicate relations using the specified strategy</li>
     * </ol>
     * 
     * @param relations All relations to process
     * @param sourceEntityNames Entity names being merged (will be redirected)
     * @param targetEntityName The entity to redirect to
     * @param strategy Strategy for merging duplicate relation descriptions
     * @return Result containing redirected relations and statistics
     */
    public RedirectResult redirectAndDeduplicate(
        @NotNull List<Relation> relations,
        @NotNull Set<String> sourceEntityNames,
        @NotNull String targetEntityName,
        @NotNull MergeStrategy strategy
    ) {
        Objects.requireNonNull(relations, "relations must not be null");
        Objects.requireNonNull(sourceEntityNames, "sourceEntityNames must not be null");
        Objects.requireNonNull(targetEntityName, "targetEntityName must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        
        int totalProcessed = relations.size();
        int selfLoopsFiltered = 0;
        
        // Step 1 & 2: Redirect and filter self-loops
        List<Relation> redirected = new ArrayList<>();
        for (Relation relation : relations) {
            Relation r = redirectRelation(relation, sourceEntityNames, targetEntityName);
            
            // Filter self-loops
            if (r.isSelfLoop()) {
                selfLoopsFiltered++;
                LOG.debugf("Filtered self-loop relation: %s -> %s", r.getSrcId(), r.getTgtId());
                continue;
            }
            
            redirected.add(r);
        }
        
        // Step 3 & 4: Group by pair and deduplicate
        Map<String, Relation> byPair = new HashMap<>();
        int duplicatesMerged = 0;
        
        for (Relation r : redirected) {
            String pairKey = createPairKey(r.getSrcId(), r.getTgtId());
            
            if (byPair.containsKey(pairKey)) {
                // Merge with existing relation
                Relation existing = byPair.get(pairKey);
                String separator = strategy.getSeparator() != null ? strategy.getSeparator() : " | ";
                Relation merged = existing.mergeWith(r, strategy.name(), separator);
                byPair.put(pairKey, merged);
                duplicatesMerged++;
                LOG.debugf("Merged duplicate relation: %s -> %s", r.getSrcId(), r.getTgtId());
            } else {
                byPair.put(pairKey, r);
            }
        }
        
        List<Relation> result = new ArrayList<>(byPair.values());
        
        LOG.infof("Redirect complete: %d processed, %d self-loops filtered, %d duplicates merged, %d final",
            totalProcessed, selfLoopsFiltered, duplicatesMerged, result.size());
        
        return new RedirectResult(result, selfLoopsFiltered, duplicatesMerged, totalProcessed);
    }
    
    /**
     * Redirects a single relation if its source or target matches any source entity.
     * 
     * @param relation The relation to potentially redirect
     * @param sourceEntityNames Entity names to look for
     * @param targetEntityName Entity to redirect to
     * @return Redirected relation (may be same instance if no change)
     */
    public Relation redirectRelation(
        @NotNull Relation relation,
        @NotNull Set<String> sourceEntityNames,
        @NotNull String targetEntityName
    ) {
        Objects.requireNonNull(relation, "relation must not be null");
        Objects.requireNonNull(sourceEntityNames, "sourceEntityNames must not be null");
        Objects.requireNonNull(targetEntityName, "targetEntityName must not be null");
        
        Relation current = relation;
        
        // Check each source entity name (case-insensitive)
        for (String sourceName : sourceEntityNames) {
            // Skip if this is already the target (no redirect needed)
            if (sourceName.equalsIgnoreCase(targetEntityName)) {
                continue;
            }
            
            // Use the redirect method from Relation class
            current = current.redirect(sourceName, targetEntityName);
        }
        
        return current;
    }
    
    /**
     * Filters out self-loop relations from a list.
     * 
     * <p>Self-loops occur when source equals target, which can happen after
     * redirecting both ends of a relation to the same merged entity.</p>
     * 
     * @param relations Relations to filter
     * @return Relations without self-loops
     */
    public List<Relation> filterSelfLoops(@NotNull List<Relation> relations) {
        Objects.requireNonNull(relations, "relations must not be null");
        
        List<Relation> filtered = new ArrayList<>();
        for (Relation r : relations) {
            if (!r.isSelfLoop()) {
                filtered.add(r);
            }
        }
        return filtered;
    }
    
    /**
     * Deduplicates relations with the same source→target pair.
     * 
     * <p>When multiple relations connect the same entity pair, they are merged:
     * <ul>
     *   <li>Descriptions are combined using the strategy</li>
     *   <li>Weights are summed</li>
     *   <li>Keywords are merged (deduplicated)</li>
     *   <li>Source chunk IDs are combined (deduplicated)</li>
     * </ul>
     * 
     * @param relations Relations to deduplicate
     * @param strategy Strategy for merging descriptions
     * @return Deduplicated relations
     */
    public List<Relation> deduplicateRelations(
        @NotNull List<Relation> relations,
        @NotNull MergeStrategy strategy
    ) {
        Objects.requireNonNull(relations, "relations must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        
        Map<String, Relation> byPair = new HashMap<>();
        String separator = strategy.getSeparator() != null ? strategy.getSeparator() : " | ";
        
        for (Relation r : relations) {
            String pairKey = createPairKey(r.getSrcId(), r.getTgtId());
            
            if (byPair.containsKey(pairKey)) {
                Relation existing = byPair.get(pairKey);
                byPair.put(pairKey, existing.mergeWith(r, strategy.name(), separator));
            } else {
                byPair.put(pairKey, r);
            }
        }
        
        return new ArrayList<>(byPair.values());
    }
    
    /**
     * Creates a unique key for a source→target pair.
     * 
     * <p>Uses lowercase for case-insensitive comparison.</p>
     * 
     * @param srcId Source entity name
     * @param tgtId Target entity name
     * @return Pair key in format "source->target"
     */
    private String createPairKey(String srcId, String tgtId) {
        return srcId.toLowerCase() + "->" + tgtId.toLowerCase();
    }
}
