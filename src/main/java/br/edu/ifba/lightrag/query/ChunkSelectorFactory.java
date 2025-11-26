package br.edu.ifba.lightrag.query;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory for selecting the appropriate ChunkSelector based on strategy.
 * 
 * <p>Uses CDI to discover all available ChunkSelector implementations
 * and provides the correct one based on the requested strategy.</p>
 * 
 * <h2>Configuration:</h2>
 * <p>Set the default strategy via application.properties:</p>
 * <pre>
 * lightrag.query.chunk-selection.strategy=vector
 * # or
 * lightrag.query.chunk-selection.strategy=weighted
 * </pre>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @Inject
 * ChunkSelectorFactory factory;
 * 
 * ChunkSelector selector = factory.getSelector(Strategy.VECTOR);
 * // or
 * ChunkSelector selector = factory.getDefaultSelector();
 * }</pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class ChunkSelectorFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(ChunkSelectorFactory.class);
    
    private final Map<Strategy, ChunkSelector> selectors;
    private final Strategy defaultStrategy;
    
    /**
     * Chunk selection strategies.
     */
    public enum Strategy {
        /**
         * Vector similarity-based selection (default).
         */
        VECTOR("vector"),
        
        /**
         * Weighted polling based on entity/relation connections.
         */
        WEIGHTED("weighted");
        
        private final String value;
        
        Strategy(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        /**
         * Parses strategy from string value.
         * 
         * @param value The string value (case-insensitive)
         * @return Matching strategy, or VECTOR as default
         */
        public static Strategy fromString(String value) {
            if (value == null || value.isBlank()) {
                return VECTOR;
            }
            
            for (Strategy s : values()) {
                if (s.value.equalsIgnoreCase(value.trim())) {
                    return s;
                }
            }
            
            logger.warn("Unknown chunk selection strategy: '{}', defaulting to VECTOR", value);
            return VECTOR;
        }
    }
    
    /**
     * Default constructor for CDI proxy.
     */
    public ChunkSelectorFactory() {
        this.selectors = new EnumMap<>(Strategy.class);
        this.defaultStrategy = Strategy.VECTOR;
    }
    
    /**
     * Constructs the factory with CDI-discovered selectors.
     * 
     * @param selectorInstances All ChunkSelector implementations
     */
    @Inject
    public ChunkSelectorFactory(Instance<ChunkSelector> selectorInstances) {
        this.selectors = new EnumMap<>(Strategy.class);
        
        for (ChunkSelector selector : selectorInstances) {
            Strategy strategy = Strategy.fromString(selector.getStrategyName());
            selectors.put(strategy, selector);
            logger.debug("Registered chunk selector: {} -> {}", strategy, selector.getClass().getSimpleName());
        }
        
        // Default to VECTOR if available
        this.defaultStrategy = selectors.containsKey(Strategy.VECTOR) ? Strategy.VECTOR : Strategy.WEIGHTED;
    }
    
    /**
     * Gets the selector for the specified strategy.
     * 
     * @param strategy The selection strategy
     * @return ChunkSelector implementation
     * @throws IllegalArgumentException if no selector is registered for the strategy
     */
    @NotNull
    public ChunkSelector getSelector(@NotNull Strategy strategy) {
        ChunkSelector selector = selectors.get(strategy);
        
        if (selector == null) {
            throw new IllegalArgumentException(
                    "No chunk selector registered for strategy: " + strategy +
                    ". Available strategies: " + selectors.keySet());
        }
        
        return selector;
    }
    
    /**
     * Gets the selector for the specified strategy string.
     * 
     * @param strategyName The strategy name (case-insensitive)
     * @return ChunkSelector implementation
     */
    @NotNull
    public ChunkSelector getSelector(@NotNull String strategyName) {
        return getSelector(Strategy.fromString(strategyName));
    }
    
    /**
     * Gets the default selector.
     * 
     * @return Default ChunkSelector (VECTOR strategy)
     */
    @NotNull
    public ChunkSelector getDefaultSelector() {
        return getSelector(defaultStrategy);
    }
    
    /**
     * Checks if a selector is available for the specified strategy.
     * 
     * @param strategy The selection strategy
     * @return true if a selector is registered
     */
    public boolean hasSelector(@NotNull Strategy strategy) {
        return selectors.containsKey(strategy);
    }
    
    /**
     * Gets the default strategy.
     * 
     * @return The default selection strategy
     */
    @NotNull
    public Strategy getDefaultStrategy() {
        return defaultStrategy;
    }
}
