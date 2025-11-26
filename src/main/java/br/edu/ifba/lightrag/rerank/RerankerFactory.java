package br.edu.ifba.lightrag.rerank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

/**
 * Factory for creating Reranker instances based on configuration.
 * 
 * <p>The factory selects the appropriate reranker implementation based on the
 * configured provider in application.properties:
 * <pre>
 * lightrag.rerank.enabled=true
 * lightrag.rerank.provider=cohere  # or "jina" or "none"
 * </pre>
 * 
 * <p>If reranking is disabled or the provider is "none", a NoOpReranker is returned.
 */
@ApplicationScoped
public class RerankerFactory {
    
    private static final Logger logger = Logger.getLogger(RerankerFactory.class);
    
    @Inject
    RerankerConfig config;
    
    @Inject
    @Named("noOpReranker")
    Reranker noOpReranker;
    
    @Inject
    @Named("cohereReranker")
    Reranker cohereReranker;
    
    @Inject
    @Named("jinaReranker")
    Reranker jinaReranker;
    
    /**
     * Gets the configured reranker implementation.
     * 
     * <p>Returns NoOpReranker if:
     * <ul>
     *   <li>Reranking is disabled</li>
     *   <li>Provider is "none"</li>
     *   <li>Provider is unknown</li>
     *   <li>Selected provider is not available (e.g., missing API key)</li>
     * </ul>
     *
     * @return the appropriate Reranker implementation
     */
    public Reranker getReranker() {
        if (!config.enabled()) {
            logger.debug("Reranking disabled, using NoOpReranker");
            return noOpReranker;
        }
        
        String provider = config.provider().toLowerCase();
        
        Reranker selectedReranker = switch (provider) {
            case "cohere" -> cohereReranker;
            case "jina" -> jinaReranker;
            case "none" -> noOpReranker;
            default -> {
                logger.warnf("Unknown reranker provider '%s', using NoOpReranker", provider);
                yield noOpReranker;
            }
        };
        
        // Verify the selected reranker is available
        if (!selectedReranker.isAvailable()) {
            logger.warnf("Reranker provider '%s' is not available (missing API key?), using NoOpReranker",
                provider);
            return noOpReranker;
        }
        
        logger.debugf("Using reranker: %s", selectedReranker.getProviderName());
        return selectedReranker;
    }
    
    /**
     * Gets a reranker by explicit provider name, ignoring configuration.
     * 
     * <p>This is useful for testing or when the caller wants to override
     * the configured provider.
     *
     * @param providerName provider name (cohere, jina, none)
     * @return the requested Reranker, or NoOpReranker if not found/available
     */
    public Reranker getReranker(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return noOpReranker;
        }
        
        String provider = providerName.toLowerCase();
        
        Reranker selectedReranker = switch (provider) {
            case "cohere" -> cohereReranker;
            case "jina" -> jinaReranker;
            case "none" -> noOpReranker;
            default -> {
                logger.warnf("Unknown reranker provider '%s', using NoOpReranker", provider);
                yield noOpReranker;
            }
        };
        
        if (!selectedReranker.isAvailable()) {
            logger.warnf("Reranker provider '%s' is not available, using NoOpReranker", provider);
            return noOpReranker;
        }
        
        return selectedReranker;
    }
    
    /**
     * Checks if the configured reranker provider is available and enabled.
     *
     * @return true if reranking is enabled and the provider is available
     */
    public boolean isRerankingAvailable() {
        if (!config.enabled()) {
            return false;
        }
        
        String provider = config.provider().toLowerCase();
        if ("none".equals(provider)) {
            return false;
        }
        
        return getReranker().isAvailable();
    }
    
    /**
     * Gets a description of the current reranker configuration.
     *
     * @return human-readable configuration description
     */
    public String describe() {
        return config.describe();
    }
}
