package br.edu.ifba.lightrag.rerank;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for reranker providers.
 * 
 * <p>Loaded from application.properties with the "lightrag.rerank" prefix.
 * Supports Cohere and Jina reranker APIs with fallback to no-op reranking.
 * 
 * <p>Example configuration:
 * <pre>
 * lightrag.rerank.enabled=true
 * lightrag.rerank.provider=cohere
 * lightrag.rerank.min-score=0.1
 * lightrag.rerank.fallback-timeout-ms=2000
 * lightrag.rerank.cohere.api-key=${COHERE_API_KEY}
 * lightrag.rerank.cohere.model=rerank-english-v3.0
 * </pre>
 */
@ConfigMapping(prefix = "lightrag.rerank")
public interface RerankerConfig {
    
    /**
     * Whether reranking is enabled.
     *
     * @return true if reranking is enabled, false otherwise
     */
    @WithDefault("false")
    boolean enabled();
    
    /**
     * The reranker provider to use.
     *
     * @return provider name (cohere, jina, or none)
     */
    @WithDefault("none")
    String provider();
    
    /**
     * Minimum relevance score threshold.
     * Chunks below this score are filtered out.
     *
     * @return minimum score (0.0 - 1.0)
     */
    @WithName("min-score")
    @WithDefault("0.1")
    double minScore();
    
    /**
     * Timeout in milliseconds before falling back to original order.
     *
     * @return timeout in milliseconds
     */
    @WithName("fallback-timeout-ms")
    @WithDefault("2000")
    int fallbackTimeoutMs();
    
    /**
     * Cohere-specific configuration.
     *
     * @return Cohere configuration
     */
    CohereConfig cohere();
    
    /**
     * Jina-specific configuration.
     *
     * @return Jina configuration
     */
    JinaConfig jina();
    
    /**
     * Configuration for Cohere reranker.
     */
    interface CohereConfig {
        
        /**
         * Cohere API key.
         *
         * @return API key, empty Optional if not configured
         */
        @WithName("api-key")
        Optional<String> apiKey();
        
        /**
         * Cohere rerank model to use.
         *
         * @return model name
         */
        @WithDefault("rerank-english-v3.0")
        String model();
    }
    
    /**
     * Configuration for Jina reranker.
     */
    interface JinaConfig {
        
        /**
         * Jina API key.
         *
         * @return API key, empty Optional if not configured
         */
        @WithName("api-key")
        Optional<String> apiKey();
        
        /**
         * Jina rerank model to use.
         *
         * @return model name
         */
        @WithDefault("jina-reranker-v2-base-multilingual")
        String model();
    }
    
    /**
     * Checks if the configuration is valid for the selected provider.
     *
     * @return true if the configuration is valid
     */
    default boolean isValid() {
        if (!enabled()) {
            return true; // Disabled is always valid
        }
        
        String prov = provider().toLowerCase();
        return switch (prov) {
            case "none" -> true;
            case "cohere" -> cohere().apiKey().filter(k -> !k.isBlank()).isPresent();
            case "jina" -> jina().apiKey().filter(k -> !k.isBlank()).isPresent();
            default -> false;
        };
    }
    
    /**
     * Gets a human-readable description of the current configuration.
     *
     * @return configuration description
     */
    default String describe() {
        if (!enabled()) {
            return "Reranking disabled";
        }
        
        String prov = provider().toLowerCase();
        return switch (prov) {
            case "none" -> "No-op reranker (passthrough)";
            case "cohere" -> "Cohere reranker (model: " + cohere().model() + ")";
            case "jina" -> "Jina reranker (model: " + jina().model() + ")";
            default -> "Unknown provider: " + provider();
        };
    }
}
