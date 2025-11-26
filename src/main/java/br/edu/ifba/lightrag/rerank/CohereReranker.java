package br.edu.ifba.lightrag.rerank;

import br.edu.ifba.lightrag.core.Chunk;
import br.edu.ifba.lightrag.core.TokenTracker;
import br.edu.ifba.lightrag.core.TokenUsage;
import br.edu.ifba.lightrag.rerank.CohereRerankClient.CohereRerankRequest;
import br.edu.ifba.lightrag.rerank.CohereRerankClient.CohereRerankResponse;
import br.edu.ifba.lightrag.rerank.CohereRerankClient.CohereRerankResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reranker implementation using Cohere Rerank API.
 * 
 * <p>Features:
 * <ul>
 *   <li>Circuit breaker to prevent cascading failures (opens after 4 failures)</li>
 *   <li>Timeout with automatic fallback to original order</li>
 *   <li>Token usage tracking for billing visibility</li>
 *   <li>Minimum score filtering</li>
 * </ul>
 * 
 * <h2>MDC Context:</h2>
 * <ul>
 *   <li><code>rerank.provider</code> - The reranker provider (cohere)</li>
 *   <li><code>rerank.model</code> - The model being used</li>
 *   <li><code>rerank.inputChunks</code> - Number of input chunks</li>
 *   <li><code>rerank.topK</code> - Requested top-K value</li>
 * </ul>
 * 
 * @since spec-007
 */
@ApplicationScoped
@Named("cohereReranker")
public class CohereReranker implements Reranker {
    
    private static final Logger LOG = LoggerFactory.getLogger(CohereReranker.class);
    private static final String PROVIDER_NAME = "cohere";
    private static final String MODEL_NAME = "cohere-rerank";
    
    private static final String MDC_PROVIDER = "rerank.provider";
    private static final String MDC_MODEL = "rerank.model";
    private static final String MDC_INPUT_CHUNKS = "rerank.inputChunks";
    private static final String MDC_TOP_K = "rerank.topK";
    
    @Inject
    @RestClient
    CohereRerankClient client;
    
    @Inject
    RerankerConfig config;
    
    @Inject
    TokenTracker tokenTracker;
    
    @Inject
    @Named("noOpReranker")
    Reranker fallbackReranker;
    
    /**
     * Reranks chunks using Cohere API with circuit breaker and timeout.
     *
     * @param query  the query string
     * @param chunks chunks to rerank
     * @param topK   maximum results to return
     * @return reranked chunks sorted by relevance
     */
    @Override
    @NotNull
    @CircuitBreaker(
        requestVolumeThreshold = 4,
        failureRatio = 0.5,
        delay = 10000,
        successThreshold = 2
    )
    @Timeout(value = 3000)
    @Fallback(fallbackMethod = "fallbackRerank")
    public List<RerankedChunk> rerank(
        @NotNull String query,
        @NotNull List<Chunk> chunks,
        int topK
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        
        long startTime = System.currentTimeMillis();
        String model = config.cohere().model();
        
        try {
            setMDC(model, chunks.size(), topK);
            
            LOG.debug("Starting rerank: {} chunks, topK={}, queryLength={}", 
                chunks.size(), topK, query.length());
            
            // Extract document texts
            List<String> documents = chunks.stream()
                .map(Chunk::getContent)
                .toList();
            
            // Build request
            CohereRerankRequest request = CohereRerankRequest.of(
                model,
                query,
                documents,
                Math.min(topK, chunks.size())
            );
            
            // Call API
            String apiKey = config.cohere().apiKey().orElseThrow(
                () -> new IllegalStateException("Cohere API key not configured"));
            String authHeader = "Bearer " + apiKey;
            CohereRerankResponse response = client.rerank(authHeader, request);
            
            // Track token usage if available
            trackTokenUsage(response, chunks.size());
            
            // Convert response to RerankedChunks
            List<RerankedChunk> result = convertToRerankedChunks(response, chunks);
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("Rerank completed - duration={}ms, input={}, output={}, filtered={}",
                duration, chunks.size(), result.size(), chunks.size() - result.size());
            
            return result;
            
        } finally {
            clearMDC();
        }
    }
    
    /**
     * Fallback method when circuit breaker is open or timeout occurs.
     * Note: Parameter types must exactly match the main method.
     *
     * @param query  the query string
     * @param chunks original chunks
     * @param topK   max results
     * @return chunks in original order with synthetic scores
     */
    @SuppressWarnings("unused")
    @NotNull
    List<RerankedChunk> fallbackRerank(
        @NotNull String query,
        @NotNull List<Chunk> chunks,
        int topK
    ) {
        try {
            setMDC(config.cohere().model(), chunks.size(), topK);
            LOG.warn("Reranker fallback triggered: returning {} chunks in original order", 
                Math.min(topK, chunks.size()));
        } finally {
            clearMDC();
        }
        return fallbackReranker.rerank(query, chunks, topK);
    }
    
    /**
     * Converts API response to RerankedChunk list.
     */
    private List<RerankedChunk> convertToRerankedChunks(
        CohereRerankResponse response,
        List<Chunk> originalChunks
    ) {
        List<CohereRerankResult> results = response.results();
        double minScore = config.minScore();
        
        // Sort by relevance score descending
        List<CohereRerankResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparingDouble(CohereRerankResult::relevanceScore).reversed());
        
        // Build reranked chunks with filtering
        List<RerankedChunk> rerankedChunks = new ArrayList<>();
        int newRank = 0;
        int filteredCount = 0;
        
        for (CohereRerankResult result : sortedResults) {
            if (result.relevanceScore() < minScore) {
                filteredCount++;
                LOG.trace("Filtering chunk at index {} with score {:.4f} < minScore {:.4f}",
                    result.index(), result.relevanceScore(), minScore);
                continue;
            }
            
            int originalIndex = result.index();
            if (originalIndex >= 0 && originalIndex < originalChunks.size()) {
                Chunk chunk = originalChunks.get(originalIndex);
                rerankedChunks.add(RerankedChunk.reranked(
                    chunk,
                    originalIndex,
                    newRank++,
                    result.relevanceScore()
                ));
            }
        }
        
        if (filteredCount > 0) {
            LOG.debug("Filtered {} chunks below minScore threshold of {}", filteredCount, minScore);
        }
        
        return rerankedChunks;
    }
    
    /**
     * Tracks token usage from Cohere API response.
     */
    private void trackTokenUsage(CohereRerankResponse response, int documentCount) {
        if (response.meta() != null && response.meta().billedUnits() != null) {
            int searchUnits = response.meta().billedUnits().searchUnits();
            // Cohere bills by search units; approximate as tokens for tracking
            TokenUsage usage = new TokenUsage(
                "RERANK",
                MODEL_NAME,
                documentCount * 100, // Approximate input tokens
                searchUnits,         // Search units as output
                Instant.now()
            );
            tokenTracker.track(usage);
            LOG.debug("Token usage tracked: {} search units for {} documents", searchUnits, documentCount);
        }
    }
    
    /**
     * Sets MDC context for structured logging.
     */
    private void setMDC(String model, int inputChunks, int topK) {
        MDC.put(MDC_PROVIDER, PROVIDER_NAME);
        MDC.put(MDC_MODEL, model);
        MDC.put(MDC_INPUT_CHUNKS, String.valueOf(inputChunks));
        MDC.put(MDC_TOP_K, String.valueOf(topK));
    }
    
    /**
     * Clears MDC context.
     */
    private void clearMDC() {
        MDC.remove(MDC_PROVIDER);
        MDC.remove(MDC_MODEL);
        MDC.remove(MDC_INPUT_CHUNKS);
        MDC.remove(MDC_TOP_K);
    }
    
    @Override
    public boolean isAvailable() {
        return config.cohere().apiKey()
            .filter(k -> !k.isBlank())
            .isPresent();
    }
    
    @Override
    @NotNull
    public String getProviderName() {
        return PROVIDER_NAME;
    }
}
