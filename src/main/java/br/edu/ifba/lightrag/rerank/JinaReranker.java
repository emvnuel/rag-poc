package br.edu.ifba.lightrag.rerank;

import br.edu.ifba.lightrag.core.Chunk;
import br.edu.ifba.lightrag.core.TokenTracker;
import br.edu.ifba.lightrag.core.TokenUsage;
import br.edu.ifba.lightrag.rerank.JinaRerankClient.JinaRerankRequest;
import br.edu.ifba.lightrag.rerank.JinaRerankClient.JinaRerankResponse;
import br.edu.ifba.lightrag.rerank.JinaRerankClient.JinaRerankResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jetbrains.annotations.NotNull;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reranker implementation using Jina Rerank API.
 * 
 * <p>Features:
 * <ul>
 *   <li>Circuit breaker to prevent cascading failures (opens after 4 failures)</li>
 *   <li>Timeout with automatic fallback to original order</li>
 *   <li>Token usage tracking for billing visibility</li>
 *   <li>Minimum score filtering</li>
 * </ul>
 */
@ApplicationScoped
@Named("jinaReranker")
public class JinaReranker implements Reranker {
    
    private static final Logger logger = Logger.getLogger(JinaReranker.class);
    private static final String PROVIDER_NAME = "jina";
    private static final String MODEL_NAME = "jina-rerank";
    
    @Inject
    @RestClient
    JinaRerankClient client;
    
    @Inject
    RerankerConfig config;
    
    @Inject
    TokenTracker tokenTracker;
    
    @Inject
    @Named("noOpReranker")
    Reranker fallbackReranker;
    
    /**
     * Reranks chunks using Jina API with circuit breaker and timeout.
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
        
        logger.debugf("JinaReranker: reranking %d chunks with query length %d",
            Integer.valueOf(chunks.size()), Integer.valueOf(query.length()));
        
        // Extract document texts
        List<String> documents = chunks.stream()
            .map(Chunk::getContent)
            .toList();
        
        // Build request
        JinaRerankRequest request = JinaRerankRequest.of(
            config.jina().model(),
            query,
            documents,
            Math.min(topK, chunks.size())
        );
        
        // Call API
        String apiKey = config.jina().apiKey().orElseThrow(
            () -> new IllegalStateException("Jina API key not configured"));
        String authHeader = "Bearer " + apiKey;
        JinaRerankResponse response = client.rerank(authHeader, request);
        
        // Track token usage if available
        trackTokenUsage(response);
        
        // Convert response to RerankedChunks
        return convertToRerankedChunks(response, chunks);
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
        logger.warnf("JinaReranker fallback: returning %d chunks in original order", 
            Integer.valueOf(Math.min(topK, chunks.size())));
        return fallbackReranker.rerank(query, chunks, topK);
    }
    
    /**
     * Converts API response to RerankedChunk list.
     */
    private List<RerankedChunk> convertToRerankedChunks(
        JinaRerankResponse response,
        List<Chunk> originalChunks
    ) {
        List<JinaRerankResult> results = response.results();
        double minScore = config.minScore();
        
        // Sort by relevance score descending
        List<JinaRerankResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparingDouble(JinaRerankResult::relevanceScore).reversed());
        
        // Build reranked chunks with filtering
        List<RerankedChunk> rerankedChunks = new ArrayList<>();
        int newRank = 0;
        
        for (JinaRerankResult result : sortedResults) {
            if (result.relevanceScore() < minScore) {
                logger.debugf("Filtering chunk at index %d with score %.4f < minScore %.4f",
                    Integer.valueOf(result.index()), Double.valueOf(result.relevanceScore()), Double.valueOf(minScore));
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
        
        logger.debugf("JinaReranker: returned %d chunks after filtering (minScore=%.2f)",
            Integer.valueOf(rerankedChunks.size()), Double.valueOf(minScore));
        
        return rerankedChunks;
    }
    
    /**
     * Tracks token usage from Jina API response.
     */
    private void trackTokenUsage(JinaRerankResponse response) {
        if (response.usage() != null) {
            int totalTokens = response.usage().totalTokens();
            TokenUsage usage = new TokenUsage(
                "RERANK",
                MODEL_NAME,
                totalTokens,  // Input tokens
                0,            // Output tokens (reranking doesn't generate output)
                Instant.now()
            );
            tokenTracker.track(usage);
            logger.debugf("JinaReranker: tracked %d tokens", Integer.valueOf(totalTokens));
        }
    }
    
    @Override
    public boolean isAvailable() {
        return config.jina().apiKey()
            .filter(k -> !k.isBlank())
            .isPresent();
    }
    
    @Override
    @NotNull
    public String getProviderName() {
        return PROVIDER_NAME;
    }
}
