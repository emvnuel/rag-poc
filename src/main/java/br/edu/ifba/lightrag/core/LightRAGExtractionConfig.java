package br.edu.ifba.lightrag.core;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/**
 * Configuration for LightRAG extraction and query enhancements.
 * 
 * <p>All properties are read from application.properties with the prefix "lightrag".
 * This configuration class covers gleaning, description summarization, keyword extraction,
 * and token budget settings introduced for official LightRAG alignment.</p>
 * 
 * <h2>Configuration Groups:</h2>
 * <ul>
 *   <li><b>gleaning</b> - Iterative extraction passes to capture missed entities</li>
 *   <li><b>description</b> - LLM-based description summarization settings</li>
 *   <li><b>query</b> - Keyword extraction and token budget settings</li>
 *   <li><b>entity</b> - Entity name normalization and source tracking</li>
 * </ul>
 * 
 * <h2>Example Configuration:</h2>
 * <pre>{@code
 * # Gleaning configuration
 * lightrag.gleaning.enabled=true
 * lightrag.gleaning.max-passes=1
 * 
 * # Description summarization
 * lightrag.description.max-tokens=500
 * lightrag.description.summarization-threshold=300
 * 
 * # Query settings
 * lightrag.query.keyword-extraction.enabled=true
 * lightrag.query.context.max-tokens=4000
 * }</pre>
 * 
 * @see DeduplicationConfig for entity resolution configuration
 */
@ConfigMapping(prefix = "lightrag")
public interface LightRAGExtractionConfig {
    
    /**
     * Gleaning configuration group.
     * 
     * <p>Gleaning is an iterative extraction technique where additional LLM calls
     * are made to capture entities and relations missed in the initial extraction pass.</p>
     * 
     * @return gleaning configuration
     */
    Gleaning gleaning();
    
    /**
     * Description summarization configuration group.
     * 
     * <p>When entity descriptions accumulate beyond a threshold, LLM-based
     * summarization produces a coherent description instead of simple concatenation.</p>
     * 
     * @return description configuration
     */
    Description description();
    
    /**
     * Query enhancement configuration group.
     * 
     * <p>Controls keyword extraction for query routing and token budget allocation
     * for context construction.</p>
     * 
     * @return query configuration
     */
    Query query();
    
    /**
     * Entity name normalization configuration group.
     * 
     * <p>Controls how entity names are normalized during extraction for consistency.</p>
     * 
     * @return entity configuration
     */
    Entity entity();
    
    /**
     * Validates configuration at startup.
     * Throws IllegalArgumentException if invalid.
     */
    default void validate() {
        // Validate gleaning passes
        if (gleaning().maxPasses() < 0) {
            throw new IllegalArgumentException(
                String.format("Gleaning max passes must be non-negative, got %d", gleaning().maxPasses())
            );
        }
        
        // Validate description tokens
        if (description().maxTokens() < 1) {
            throw new IllegalArgumentException(
                String.format("Description max tokens must be positive, got %d", description().maxTokens())
            );
        }
        
        if (description().summarizationThreshold() < 1) {
            throw new IllegalArgumentException(
                String.format("Summarization threshold must be positive, got %d", description().summarizationThreshold())
            );
        }
        
        // Validate query token budget ratios sum to approximately 1.0
        double ratioSum = query().context().entityBudgetRatio() 
                        + query().context().relationBudgetRatio() 
                        + query().context().chunkBudgetRatio();
        if (Math.abs(ratioSum - 1.0) > 0.01) {
            throw new IllegalArgumentException(
                String.format(
                    "Token budget ratios must sum to 1.0, got %.3f (entity=%.2f, relation=%.2f, chunk=%.2f)",
                    ratioSum, 
                    query().context().entityBudgetRatio(),
                    query().context().relationBudgetRatio(),
                    query().context().chunkBudgetRatio()
                )
            );
        }
        
        // Validate entity name max length
        if (entity().nameMaxLength() < 1) {
            throw new IllegalArgumentException(
                String.format("Entity name max length must be positive, got %d", entity().nameMaxLength())
            );
        }
        
        // Validate max source IDs
        if (entity().maxSourceIds() < 1) {
            throw new IllegalArgumentException(
                String.format("Max source IDs must be positive, got %d", entity().maxSourceIds())
            );
        }
    }
    
    /**
     * Gleaning configuration for iterative extraction.
     * 
     * <p>Gleaning helps capture entities missed in the initial extraction by
     * running additional LLM calls with a continuation prompt asking for more entities.</p>
     */
    interface Gleaning {
        /**
         * Enable iterative gleaning for entity extraction.
         * 
         * <p>When enabled, after the initial extraction pass, additional passes
         * are run to capture entities that were missed.</p>
         * 
         * @return true if gleaning is enabled, default true
         */
        @WithDefault("true")
        boolean enabled();
        
        /**
         * Maximum number of gleaning passes after initial extraction.
         * 
         * <p>Set to 0 to disable gleaning even if enabled=true.
         * The official LightRAG implementation uses 1 gleaning pass.</p>
         * 
         * @return maximum gleaning passes, default 1
         */
        @WithName("max-passes")
        @WithDefault("1")
        @Min(0)
        @Max(5)
        int maxPasses();
    }
    
    /**
     * Description summarization configuration.
     * 
     * <p>Controls when and how entity descriptions are summarized using LLM
     * instead of simple concatenation.</p>
     */
    interface Description {
        /**
         * Maximum tokens allowed for entity descriptions.
         * 
         * <p>When accumulated descriptions exceed this limit, summarization is triggered.</p>
         * 
         * @return max description tokens, default 500
         */
        @WithName("max-tokens")
        @WithDefault("500")
        @Min(50)
        int maxTokens();
        
        /**
         * Token threshold for triggering LLM summarization.
         * 
         * <p>When total description tokens exceed this threshold, an LLM call
         * is made to produce a coherent summary. Should be less than maxTokens.</p>
         * 
         * @return summarization threshold in tokens, default 300
         */
        @WithName("summarization-threshold")
        @WithDefault("300")
        @Min(50)
        int summarizationThreshold();
        
        /**
         * Separator used when concatenating descriptions below the threshold.
         * 
         * @return description separator, default " | "
         */
        @WithDefault(" | ")
        String separator();
    }
    
    /**
     * Query enhancement configuration.
     */
    interface Query {
        /**
         * Keyword extraction configuration.
         */
        @WithName("keyword-extraction")
        KeywordExtraction keywordExtraction();
        
        /**
         * Context token budget configuration.
         */
        Context context();
    }
    
    /**
     * Keyword extraction configuration for query routing.
     * 
     * <p>The official LightRAG implementation extracts high-level and low-level
     * keywords from queries to route retrieval appropriately.</p>
     */
    interface KeywordExtraction {
        /**
         * Enable LLM-based keyword extraction for queries.
         * 
         * <p>When enabled, queries are processed to extract high-level (thematic)
         * and low-level (entity) keywords for smarter retrieval routing.</p>
         * 
         * @return true if keyword extraction is enabled, default true
         */
        @WithDefault("true")
        boolean enabled();
        
        /**
         * Cache TTL for keyword extraction results in seconds.
         * 
         * <p>Keyword extraction results are cached to avoid redundant LLM calls
         * for repeated queries.</p>
         * 
         * @return cache TTL in seconds, default 3600 (1 hour)
         */
        @WithName("cache-ttl")
        @WithDefault("3600")
        @Min(0)
        int cacheTtl();
    }
    
    /**
     * Token budget configuration for query context construction.
     * 
     * <p>Controls how the total token budget is allocated across different
     * context sources (entities, relations, chunks).</p>
     */
    interface Context {
        /**
         * Maximum tokens for combined query context.
         * 
         * <p>Total budget allocated across entities, relations, and chunks.</p>
         * 
         * @return max context tokens, default 4000
         */
        @WithName("max-tokens")
        @WithDefault("4000")
        @Min(100)
        int maxTokens();
        
        /**
         * Fraction of token budget allocated to entity context.
         * 
         * <p>Used in local and hybrid query modes.</p>
         * 
         * @return entity budget ratio [0.0, 1.0], default 0.4
         */
        @WithName("entity-budget-ratio")
        @WithDefault("0.4")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        double entityBudgetRatio();
        
        /**
         * Fraction of token budget allocated to relation context.
         * 
         * <p>Used in global and hybrid query modes.</p>
         * 
         * @return relation budget ratio [0.0, 1.0], default 0.3
         */
        @WithName("relation-budget-ratio")
        @WithDefault("0.3")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        double relationBudgetRatio();
        
        /**
         * Fraction of token budget allocated to chunk context.
         * 
         * <p>Used in mix and naive query modes for document chunks.</p>
         * 
         * @return chunk budget ratio [0.0, 1.0], default 0.3
         */
        @WithName("chunk-budget-ratio")
        @WithDefault("0.3")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        double chunkBudgetRatio();
    }
    
    /**
     * Entity name normalization configuration.
     */
    interface Entity {
        /**
         * Maximum character length for entity names.
         * 
         * <p>Names exceeding this length are truncated during extraction.</p>
         * 
         * @return max name length, default 500
         */
        @WithName("name-max-length")
        @WithDefault("500")
        @Min(10)
        int nameMaxLength();
        
        /**
         * Maximum number of source chunk IDs to track per entity/relation.
         * 
         * <p>When exceeded, oldest entries are evicted (FIFO).</p>
         * 
         * @return max source IDs, default 50
         */
        @WithName("max-source-ids")
        @WithDefault("50")
        @Min(1)
        int maxSourceIds();
    }
}
