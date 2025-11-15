package br.edu.ifba.lightrag.core;

import java.time.Duration;
import java.util.List;

/**
 * Result of the entity resolution process.
 * 
 * Contains:
 * - Resolved entities (after deduplication)
 * - Statistics about the resolution process
 * - Performance metrics
 * 
 * @param resolvedEntities List of entities after deduplication
 * @param originalEntityCount Number of entities before resolution
 * @param resolvedEntityCount Number of entities after resolution
 * @param duplicatesRemoved Number of duplicate entities merged
 * @param clustersFound Number of clusters identified
 * @param processingTime Duration of the resolution process
 */
public record EntityResolutionResult(
    List<Entity> resolvedEntities,
    int originalEntityCount,
    int resolvedEntityCount,
    int duplicatesRemoved,
    int clustersFound,
    Duration processingTime
) {
    
    /**
     * Creates an EntityResolutionResult with validation.
     */
    public EntityResolutionResult {
        if (resolvedEntities == null) {
            throw new IllegalArgumentException("resolvedEntities cannot be null");
        }
        if (originalEntityCount < 0) {
            throw new IllegalArgumentException("originalEntityCount cannot be negative");
        }
        if (resolvedEntityCount < 0) {
            throw new IllegalArgumentException("resolvedEntityCount cannot be negative");
        }
        if (duplicatesRemoved < 0) {
            throw new IllegalArgumentException("duplicatesRemoved cannot be negative");
        }
        if (clustersFound < 0) {
            throw new IllegalArgumentException("clustersFound cannot be negative");
        }
        if (processingTime == null) {
            throw new IllegalArgumentException("processingTime cannot be null");
        }
    }
    
    /**
     * Returns the deduplication rate (percentage of entities removed).
     * 
     * @return Deduplication rate [0.0, 1.0]
     */
    public double deduplicationRate() {
        if (originalEntityCount == 0) return 0.0;
        return (double) duplicatesRemoved / originalEntityCount;
    }
    
    /**
     * Returns the average processing time per entity in milliseconds.
     * 
     * @return Average processing time in milliseconds
     */
    public double averageProcessingTimePerEntity() {
        if (originalEntityCount == 0) return 0.0;
        return processingTime.toMillis() / (double) originalEntityCount;
    }
    
    /**
     * Returns true if any duplicates were found and removed.
     * 
     * @return true if duplicates were removed
     */
    public boolean hadDuplicates() {
        return duplicatesRemoved > 0;
    }
    
    /**
     * Returns a formatted string representation for logging.
     * 
     * @return Formatted string with resolution statistics
     */
    public String toLogString() {
        return String.format(
            "Entity Resolution: %d → %d entities (-%d duplicates, %.1f%% reduction) | %d clusters | %dms (%.2fms/entity)",
            originalEntityCount,
            resolvedEntityCount,
            duplicatesRemoved,
            deduplicationRate() * 100,
            clustersFound,
            processingTime.toMillis(),
            averageProcessingTimePerEntity()
        );
    }
}
