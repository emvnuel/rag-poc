package br.edu.ifba.lightrag.core;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Configuration for entity deduplication.
 * 
 * All properties are read from application.properties with the prefix
 * "lightrag.entity.resolution".
 */
@ConfigMapping(prefix = "lightrag.entity.resolution")
public interface DeduplicationConfig {
    
    /**
     * Feature toggle for entity resolution.
     * 
     * @return true if entity resolution is enabled
     */
    @WithDefault("true")
    boolean enabled();
    
    /**
     * Similarity configuration group.
     */
    Similarity similarity();
    
    /**
     * Maximum number of aliases to store per entity.
     * Default: 5
     * 
     * @return Maximum aliases
     */
    @WithName("max.aliases")
    @WithDefault("5")
    int maxAliases();
    
    /**
     * Clustering configuration group.
     */
    Clustering clustering();
    
    /**
     * Weight configuration group.
     */
    Weight weight();
    
    /**
     * Batch configuration group.
     */
    Batch batch();
    
    /**
     * Parallel processing configuration group.
     */
    Parallel parallel();
    
    /**
     * Semantic similarity configuration group (Phase 3 feature).
     */
    Semantic semantic();
    
    /**
     * Logging configuration group.
     */
    Log log();
    
    /**
     * Validates configuration at startup.
     * Throws IllegalArgumentException if invalid.
     */
    default void validate() {
        // Validate weights sum to 1.0
        double weightSum = weight().jaccard() + weight().containment() 
                         + weight().edit() + weight().abbreviation();
        if (Math.abs(weightSum - 1.0) > 0.01) {
            throw new IllegalArgumentException(
                String.format(
                    "Similarity weights must sum to 1.0, got %.3f (jaccard=%.2f, containment=%.2f, edit=%.2f, abbrev=%.2f)",
                    weightSum, weight().jaccard(), weight().containment(), weight().edit(), weight().abbreviation()
                )
            );
        }
        
        // Validate threshold
        if (similarity().threshold() < 0.0 || similarity().threshold() > 1.0) {
            throw new IllegalArgumentException(
                String.format("Similarity threshold must be in [0.0, 1.0], got %.3f", similarity().threshold())
            );
        }
        
        // Validate clustering algorithm
        if (!clustering().algorithm().equals("threshold") && !clustering().algorithm().equals("dbscan")) {
            throw new IllegalArgumentException(
                String.format("Clustering algorithm must be 'threshold' or 'dbscan', got '%s'", clustering().algorithm())
            );
        }
    }
    
    // Convenience methods for backward compatibility with existing test code
    default double similarityThreshold() {
        return similarity().threshold();
    }
    
    default double weightJaccard() {
        return weight().jaccard();
    }
    
    default double weightContainment() {
        return weight().containment();
    }
    
    default double weightEdit() {
        return weight().edit();
    }
    
    default double weightAbbreviation() {
        return weight().abbreviation();
    }
    
    /**
     * Similarity configuration.
     */
    interface Similarity {
        /**
         * Threshold for considering entities as duplicates [0.0, 1.0].
         * Default: 0.75
         */
        @WithDefault("0.75")
        @Min(0)
        @Max(1)
        double threshold();
    }
    
    /**
     * Clustering configuration.
     */
    interface Clustering {
        /**
         * Clustering algorithm: "threshold" or "dbscan".
         * Default: "threshold"
         */
        @WithDefault("threshold")
        String algorithm();
    }
    
    /**
     * Weight configuration for similarity metrics.
     */
    interface Weight {
        /**
         * Weight for Jaccard similarity [0.0, 1.0].
         * Default: 0.35
         */
        @WithDefault("0.35")
        @Min(0)
        @Max(1)
        double jaccard();
        
        /**
         * Weight for containment metric [0.0, 1.0].
         * Default: 0.25
         */
        @WithDefault("0.25")
        @Min(0)
        @Max(1)
        double containment();
        
        /**
         * Weight for Levenshtein (edit distance) [0.0, 1.0].
         * Default: 0.30
         */
        @WithDefault("0.30")
        @Min(0)
        @Max(1)
        double edit();
        
        /**
         * Weight for abbreviation matching [0.0, 1.0].
         * Default: 0.10
         */
        @WithDefault("0.10")
        @Min(0)
        @Max(1)
        double abbreviation();
    }
    
    /**
     * Batch processing configuration.
     */
    interface Batch {
        /**
         * Batch size for processing entities.
         * Default: 200
         */
        @WithDefault("200")
        int size();
    }
    
    /**
     * Parallel processing configuration.
     */
    interface Parallel {
        /**
         * Enable parallel processing.
         * Default: true
         */
        @WithDefault("true")
        boolean enabled();
        
        /**
         * Number of threads for parallel processing.
         * Default: 4
         */
        @WithDefault("4")
        int threads();
    }
    
    /**
     * Semantic similarity configuration (Phase 3).
     */
    interface Semantic {
        /**
         * Enable semantic similarity using embeddings.
         * Default: false
         */
        @WithDefault("false")
        boolean enabled();
        
        /**
         * Weight for semantic similarity [0.0, 1.0].
         * Default: 0.40
         */
        @WithDefault("0.40")
        @Min(0)
        @Max(1)
        double weight();
    }
    
    /**
     * Logging configuration.
     */
    interface Log {
        /**
         * Log entity merge decisions at INFO level.
         * Default: true
         */
        @WithDefault("true")
        boolean merges();
        
        /**
         * Log detailed similarity scores at DEBUG level.
         * Default: false
         */
        @WithName("similarity.scores")
        @WithDefault("false")
        boolean similarityScores();
    }
}
